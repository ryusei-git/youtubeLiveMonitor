package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.ResourceHistoryPoint;
import com.example.monitor.dto.ResourceSnapshotResponse;
import com.example.monitor.dto.ResourceSnapshotResponse.ApplicationUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.HelperUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.ProcessUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.RecorderUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.ServiceUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.SystemUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.Warning;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.DiskSpaceUtils;
import com.example.monitor.util.RecordingPathUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 端末全体とこのサービス（アプリ本体・録画プロセス・アプリが起動したその他の外部プロセス）が使うリソースを計測し、直近 24 時間の推移を持つ。
 *
 * <p>計測には OSHI を使う。自前で {@code /proc} を読むより、プロセスごとの CPU・メモリの
 * 取り方を間違えにくいため（親 Issue #139 の決定）。
 *
 * <p>CPU 使用率とネットワーク量は「2 回の計測の差」でしか出せないので、1 分ごとの記録（{@link #record()}）で
 * 前回の記録との差を取る。API（{@link #snapshot()}）は測り直さず、直前の記録の値を返す。
 * 計測は全プロセス（この端末で数百）のコマンドラインを調べる。以前は API のたびに測り直していて、
 * ダッシュボードを開いている間は 1 分ごとの記録とは別に毎分同じ列挙をしていた（#190）。
 * 推移は再起動で消えてよいのでメモリ上にだけ持つ（DB に書くと書き込みが 1 分ごとに増えるだけ）。
 *
 * <p>「このサービス」には、録画プロセス（アプリを再起動しても動き続ける yt-dlp）に加えて、アプリが起動したその他の
 * 外部プロセス（耳キスの検出・詰め替え・サムネイルの ffmpeg、「端末に保存」の yt-dlp など）も含める。含めないと、
 * CPU の注意が出ているのに「このサービス」は低く見え、原因がこのサービスの外にあると読めてしまう。
 * 数えるのは 1 分ごとの記録の時点で動いているものだけで、記録と記録の間に始まって終わった短いもの
 * （サムネイルの ffmpeg など）は数えられない。
 *
 * <p>CLI では定期処理が動かず前回値が作られないので、Bean ごと作らない。
 */
@Service
@Profile("!cli")
@RequiredArgsConstructor
public class ResourceMonitorService {

    /** 推移を持つ件数。1 分ごとに 24 時間分。 */
    private static final int HISTORY_SIZE = 24 * 60;

    /**
     * CPU の注意の目安（%）。85% を超えると新しい録画や監視の処理が待たされ始める余裕の無さのため。
     * 一瞬の山（ビルドやサムネイル生成）で注意が出ないよう、{@link #CPU_WARNING_MINUTES} 分続いたときだけ出す。
     */
    private static final double CPU_WARNING_PERCENT = 85;

    /** CPU の注意を出すまでに目安超えが続く分数。一時的な山と、処理しきれていない状態を分けるため。 */
    private static final int CPU_WARNING_MINUTES = 5;

    /**
     * 空きメモリの注意の目安（合計に対する割合）。10% を切るとページキャッシュを削ってでも確保する状態で、
     * さらに録画が増えるとスワップや OOM killer に至るため、その手前で知らせる。
     */
    private static final double MEMORY_AVAILABLE_WARNING_RATIO = 0.10;

    /**
     * スワップ使用の注意の目安（合計に対する割合）。少量のスワップは使われていない領域の退避で正常だが、
     * 半分を超えるのは実メモリが恒常的に足りていない兆候のため。
     */
    private static final double SWAP_USED_WARNING_RATIO = 0.50;

    /**
     * 録画の保存先の空きの注意の目安（合計に対する割合）。長い配信は 1 本で数十 GB になり、
     * 10% を切ると録画の途中で書き込めなくなるおそれがあるため。
     */
    private static final double DISK_FREE_WARNING_RATIO = 0.10;

    /**
     * 差を取る 2 回の計測の間隔の下限（ミリ秒）。記録が間を置かずに続くと（定期処理が遅れを取り戻すときなど）
     * 差がほぼ 0 になり、使用率が 0% や極端な値に振れるので、そのときは「分からない」（{@code null}）として返す。
     */
    private static final long MIN_WINDOW_MILLIS = 1000;

    private final RecordingRepository recordingRepository;
    private final MonitorProperties monitorProperties;

    private final SystemInfo systemInfo = new SystemInfo();
    private final Deque<ResourceHistoryPoint> history = new ArrayDeque<>();

    /** 直前の 1 分ごとの記録の時点の値。まだ記録していなければ {@code null}。 */
    private volatile Baseline baseline;

    /** 直前の 1 分ごとの記録で測った値（注意を含む）。まだ記録していなければ {@code null}。 */
    private volatile ResourceSnapshotResponse latest;

    /**
     * 今の値を返す。直前の 1 分ごとの記録の値なので最大 1 分古く、CPU 使用率とネットワーク量は
     * その 1 つ前の記録からの平均。
     *
     * <p>まだ記録していない起動直後だけはその場で測る。差を取る前回値が無いので、CPU 使用率と
     * ネットワーク量は {@code null}。
     *
     * @return 今のリソースと注意
     */
    public ResourceSnapshotResponse snapshot() {
        ResourceSnapshotResponse recorded = latest;
        return recorded != null ? recorded : withWarnings(measure(null).response());
    }

    /**
     * 今の値を推移に 1 件加え、次の差分計算の基準と、API（{@link #snapshot()}）が返す値にする。
     * 1 分ごとの定期処理から呼ぶ。
     */
    public synchronized void record() {
        Measurement measurement = measure(baseline);
        baseline = measurement.baseline();
        SystemUsage system = measurement.response().system();
        ServiceUsage service = measurement.response().service();
        ResourceHistoryPoint point = new ResourceHistoryPoint(
                measurement.response().measuredAt(),
                system.cpuPercent(),
                100.0 * system.memoryUsedBytes() / system.memoryTotalBytes(),
                service.cpuPercent(),
                service.memoryBytes(),
                service.recorders().size(),
                system.networkReceiveBytesPerSecond());
        synchronized (history) {
            if (history.size() == HISTORY_SIZE) history.removeFirst();
            history.addLast(point);
        }
        // CPU の注意は推移の末尾（今回の記録を含む）を見るので、推移に加えた後に判定する
        latest = withWarnings(measurement.response());
    }

    /**
     * 直近 24 時間の推移を古い順に返す。
     *
     * @return 1 分ごとの記録
     */
    public List<ResourceHistoryPoint> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    private Measurement measure(Baseline prior) {
        long now = System.currentTimeMillis();
        long windowMillis = prior == null ? 0 : now - prior.timeMillis();
        boolean hasWindow = windowMillis >= MIN_WINDOW_MILLIS;

        CentralProcessor processor = systemInfo.getHardware().getProcessor();
        int cores = processor.getLogicalProcessorCount();
        long[] ticks = processor.getSystemCpuLoadTicks();
        Double systemCpu = hasWindow ? 100 * processor.getSystemCpuLoadBetweenTicks(prior.cpuTicks()) : null;
        double load = processor.getSystemLoadAverage(1)[0];

        GlobalMemory memory = systemInfo.getHardware().getMemory();
        // 既定の一覧はループバックやハードウェアアドレスを持たない仮想インターフェースを除く。
        // VPN（tailscale 等）は物理インターフェースと同じ通信を二重に数えてしまうため、除かれるのが都合がよい。
        long received = 0;
        long sent = 0;
        for (NetworkIF network : systemInfo.getHardware().getNetworkIFs()) {
            received += network.getBytesRecv();
            sent += network.getBytesSent();
        }

        String diskPath = monitorProperties.recording().directory();
        DiskSpaceUtils.Capacity disk = DiskSpaceUtils.read(Path.of(diskPath));

        SystemUsage system = new SystemUsage(
                systemCpu, cores, load < 0 ? null : load,
                memory.getTotal(), memory.getTotal() - memory.getAvailable(), memory.getAvailable(),
                memory.getVirtualMemory().getSwapTotal(), memory.getVirtualMemory().getSwapUsed(),
                diskPath, disk.totalBytes(), disk.usableBytes(),
                hasWindow ? (received - prior.receivedBytes()) * 1000 / windowMillis : null,
                hasWindow ? (sent - prior.sentBytes()) * 1000 / windowMillis : null);

        OperatingSystem os = systemInfo.getOperatingSystem();
        List<OSProcess> processes = processCandidates(os);
        Map<Integer, List<OSProcess>> childrenByParent = processes.stream()
                .collect(Collectors.groupingBy(OSProcess::getParentProcessID));
        Map<Integer, OSProcess> tracked = new HashMap<>();
        CpuMeter cpu = new CpuMeter(prior, hasWindow, windowMillis, cores, tracked);

        OSProcess self = os.getCurrentProcess();
        Runtime runtime = Runtime.getRuntime();
        ApplicationUsage application = new ApplicationUsage(
                self.getProcessID(), cpu.percent(self), self.getResidentMemory(),
                runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), self.getThreadCount());

        Path recordingRoot = Path.of(diskPath).toAbsolutePath().normalize();
        Map<String, String> channelNames = recordingChannelNames();
        List<RecorderUsage> recorders = new ArrayList<>();
        for (OSProcess process : processes) {
            String videoId = recordingVideoId(process, recordingRoot);
            if (videoId == null) continue;
            List<ProcessUsage> children = new ArrayList<>();
            collectDescendants(process.getProcessID(), childrenByParent, cpu, children);
            recorders.add(new RecorderUsage(process.getProcessID(), process.getName(),
                    channelNames.getOrDefault(videoId, videoId), cpu.percent(process), process.getResidentMemory(),
                    children));
        }

        List<HelperUsage> helpers = helperUsages(processes, self.getProcessID(), recorders, childrenByParent, cpu);

        ResourceSnapshotResponse response = new ResourceSnapshotResponse(LocalDateTime.now(), system,
                serviceUsage(application, recorders, helpers), List.of());
        return new Measurement(response, new Baseline(now, ticks, received, sent, tracked));
    }

    /**
     * このサービスのプロセスの候補を、OSHI のプロセスとして返す。候補は次の 2 つと、それぞれの子孫。
     * <ul>
     *   <li>コマンドラインに {@code yt-dlp} を含むもの（録画プロセスの候補）</li>
     *   <li>アプリ（この JVM）の子孫。耳キスの検出・詰め替え・サムネイルの ffmpeg、「端末に保存」の yt-dlp など</li>
     * </ul>
     *
     * <p>OSHI で全プロセスを列挙すると、プロセスごとに {@code /proc} の複数のファイルを読んで文字列や表を作るため、
     * この端末（約 380 プロセス）で 1 回に約 45MiB を確保して捨てていた。1 分ごとの記録なので、画面を開いていなくても
     * 1 日で約 63GiB の短命のごみになる（#201）。絞り込みはコマンドラインだけを読む JDK の {@link ProcessHandle}
     * （1 回で約 0.2MiB）で行い、OSHI には残ったプロセスだけを聞く。
     *
     * <p>録画プロセスの候補は全プロセスを見ていたときと変わらない。yt-dlp は Python のスクリプトなので、JDK のコマンドライン
     * （実行ファイルと引数）にもスクリプトのパスとして {@code yt-dlp} が入り、候補から漏れない。録画プロセスかどうかは
     * 今までどおり {@code recordingVideoId} が OSHI の引数で決め、子孫の木も OSHI の親 PID で組む。
     * 1 つのプロセスは全プロセスを列挙していたときと同じく 1 回だけ載せ、終わっていたもの（OSHI が {@code null}）は載せない。
     *
     * <p>アプリの子孫を加えるのは、録画以外の外部プロセスが「このサービス」から漏れていたため。ffmpeg・ffprobe は
     * コマンドラインに {@code yt-dlp} を含まず、「端末に保存」の yt-dlp は出力先が録画の保存先の外なので録画プロセスにならない。
     * 「ffmpeg を含むもの」のようにコマンドラインで探さないのは、利用者が手で動かしたものや、それを {@code grep} している
     * シェルまで数えてしまうため（{@code docs/pitfalls.md}「録画中かの判定は、動画 ID を含むだけの grep・tail で誤検知する」と同じ理由）。
     * アプリの子孫は数個なので、OSHI に聞くプロセスはほとんど増えない。録画の yt-dlp はアプリを再起動すると
     * 子孫でなくなる（JVM より長く動き続ける）ので、1 つ目の条件は外さない。
     */
    private static List<OSProcess> processCandidates(OperatingSystem os) {
        Map<Integer, OSProcess> found = new LinkedHashMap<>();
        Stream<ProcessHandle> recorders = ProcessHandle.allProcesses()
                .filter(process -> process.info().commandLine().filter(line -> line.contains("yt-dlp")).isPresent())
                .flatMap(process -> Stream.concat(Stream.of(process), process.descendants()));
        Stream.concat(recorders, ProcessHandle.current().descendants())
                .forEach(process -> found.computeIfAbsent((int) process.pid(), os::getProcess));
        return List.copyOf(found.values());
    }

    /**
     * アプリが起動した、録画プロセス以外の外部プロセスを、アプリの直接の子ごとにまとめる。
     *
     * <p>録画プロセスとその子孫として数えたものは除く。今のアプリが起動した録画の yt-dlp はアプリの子でもあるので、
     * 除かないと「このサービス」の合計に二重に入る。OSHI のプロセスを受け取る形に切り出したのは、
     * モックのプロセスで二重に数えないことを確かめるため。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     *
     * @param processes        このサービスのプロセスの候補（{@code processCandidates} の戻り値）
     * @param selfPid          アプリ（この JVM）のプロセス ID
     * @param recorders        録画プロセスとして数えたもの
     * @param childrenByParent 親のプロセス ID → 子のプロセス
     * @param cpu              CPU 使用率の計算（計測したプロセスを次回の差分のために覚える）
     * @return その他の外部プロセス。アプリの直接の子ごとに、その先の子孫を {@code children} に持つ
     */
    static List<HelperUsage> helperUsages(List<OSProcess> processes, int selfPid, List<RecorderUsage> recorders,
                                          Map<Integer, List<OSProcess>> childrenByParent, CpuMeter cpu) {
        // 録画プロセスとその子孫として数えたものは除く。今のアプリが起動した録画の yt-dlp はアプリの子でもあるため、二重に数えない
        Set<Integer> counted = new HashSet<>();
        for (RecorderUsage recorder : recorders) {
            counted.add(recorder.pid());
            for (ProcessUsage child : recorder.children()) {
                counted.add(child.pid());
            }
        }
        List<HelperUsage> helpers = new ArrayList<>();
        for (OSProcess process : processes) {
            // アプリの直接の子を親の行にし、その先（yt-dlp が起動する ffmpeg など）は子として親の下に付ける
            if (process.getParentProcessID() != selfPid || counted.contains(process.getProcessID())) continue;
            List<ProcessUsage> children = new ArrayList<>();
            collectDescendants(process.getProcessID(), childrenByParent, cpu, children);
            helpers.add(new HelperUsage(process.getProcessID(), process.getName(), helperPurpose(process.getArguments()),
                    cpu.percent(process), process.getResidentMemory(), children));
        }
        return helpers;
    }

    /**
     * アプリ本体・録画プロセス・その他の外部プロセス（どれも子孫を含む）を足し合わせて、「このサービス」の値にする。
     *
     * <p>CPU 使用率は、1 つでも分からない（{@code null}）ものがあれば合計も {@code null} にする（{@code add} の理由と同じ）。
     * 実メモリは常に分かるので、そのまま足す。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     *
     * @param application アプリ本体
     * @param recorders   録画プロセス
     * @param helpers     その他の外部プロセス
     * @return このサービス全体の値
     */
    static ServiceUsage serviceUsage(ApplicationUsage application, List<RecorderUsage> recorders,
                                     List<HelperUsage> helpers) {
        Double serviceCpu = application.cpuPercent();
        long serviceMemory = application.memoryBytes();
        for (RecorderUsage recorder : recorders) {
            serviceCpu = add(serviceCpu, recorder.cpuPercent());
            serviceMemory += recorder.memoryBytes();
            for (ProcessUsage child : recorder.children()) {
                serviceCpu = add(serviceCpu, child.cpuPercent());
                serviceMemory += child.memoryBytes();
            }
        }
        for (HelperUsage helper : helpers) {
            serviceCpu = add(serviceCpu, helper.cpuPercent());
            serviceMemory += helper.memoryBytes();
            for (ProcessUsage child : helper.children()) {
                serviceCpu = add(serviceCpu, child.cpuPercent());
                serviceMemory += child.memoryBytes();
            }
        }
        return new ServiceUsage(serviceCpu, serviceMemory, application, recorders, helpers);
    }

    /**
     * 録画プロセスなら出力先の動画 ID を返す。
     *
     * <p>アプリを再起動しても yt-dlp は生き残るため、Java の子プロセスとしてではなく
     * コマンドラインで見分ける。{@code yt-dlp} を含むだけでは利用者が手で動かしたものまで数えるので、
     * 出力先（{@code -o}）が録画の保存先の中にあるものに限る。出力先は相対パスで渡しているため、
     * そのプロセスの作業ディレクトリを起点に解決する。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     */
    static String recordingVideoId(OSProcess process, Path recordingRoot) {
        List<String> args = process.getArguments();
        if (args.stream().noneMatch(arg -> arg.contains("yt-dlp"))) return null;
        int option = args.indexOf("-o");
        if (option < 0 || option + 1 >= args.size()) return null;
        try {
            String workingDirectory = process.getCurrentWorkingDirectory();
            Path base = workingDirectory == null || workingDirectory.isEmpty() ? Path.of("") : Path.of(workingDirectory);
            Path output = base.resolve(args.get(option + 1)).toAbsolutePath().normalize();
            return output.startsWith(recordingRoot) ? RecordingPathUtils.videoId(output) : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * アプリが起動したプロセスの用途を、コマンドラインの引数から見分けて返す。
     *
     * <p>起動するときに用途を記録する方法もあるが、起動する箇所（{@code ExternalCommandRunner}・{@code PcmDecoder}・
     * {@code DeviceDownloadService}・{@code NativeDirectoryPickerService}）すべてに手を入れることになるため、
     * 各箇所が組み立てる引数の特徴で見分ける。表示の手がかりにすぎないので、見分けられないものは「その他」にして数え続ける。
     * 起動する箇所の引数を変えたら、ここも合わせる。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     *
     * @param args OSHI が返す引数（先頭は実行ファイル）。取れなければ空
     * @return 画面に出す用途
     */
    static String helperPurpose(List<String> args) {
        String executable = args.isEmpty() ? "" : args.getFirst();
        // フォルダ選択の PowerShell のスクリプトには保存先のパスが入るので、yt-dlp の判定より先に見る
        if (executable.contains("zenity") || executable.contains("powershell")) return "フォルダの選択";
        if (args.stream().anyMatch(arg -> arg.contains("yt-dlp"))) {
            // 録画・サービスへの保存の yt-dlp は録画プロセスとして数えるので、ここに来る -o 付きは端末への保存だけ
            return args.contains("-o") ? "端末に保存" : "動画の URL の確認";
        }
        if (args.contains("s16le")) return "耳キスの検出";
        if (args.contains("-frames:v")) return "サムネイルの作成";
        if (args.contains("copy")) return "MP4 への詰め替え";
        if (executable.contains("ffprobe")) return "動画ファイルの確認";
        return "その他";
    }

    private static void collectDescendants(int pid, Map<Integer, List<OSProcess>> childrenByParent,
                                           CpuMeter cpu, List<ProcessUsage> out) {
        for (OSProcess child : childrenByParent.getOrDefault(pid, List.of())) {
            out.add(new ProcessUsage(child.getProcessID(), child.getName(), cpu.percent(child), child.getResidentMemory()));
            collectDescendants(child.getProcessID(), childrenByParent, cpu, out);
        }
    }

    /** 録画中の動画 ID からチャンネル名を引く表。チャンネルが削除された録画は載せない（動画 ID で表示する）。 */
    private Map<String, String> recordingChannelNames() {
        Map<String, String> names = new HashMap<>();
        for (Recording recording : recordingRepository.findWithChannelByStatus(RecordingStatus.RECORDING)) {
            if (recording.getChannel() != null) {
                names.put(recording.getVideoId(), recording.getChannel().getChannelName());
            }
        }
        return names;
    }

    private ResourceSnapshotResponse withWarnings(ResourceSnapshotResponse measured) {
        return new ResourceSnapshotResponse(measured.measuredAt(), measured.system(), measured.service(),
                warnings(measured.system()));
    }

    private List<Warning> warnings(SystemUsage system) {
        List<ResourceHistoryPoint> recent;
        // 推移は最大 24 時間分（1440 件）あるので、全体は複製せずに末尾から目安の分数だけを取り出す
        synchronized (history) {
            recent = history.reversed().stream().limit(CPU_WARNING_MINUTES).toList();
        }
        return evaluateWarnings(system, recent);
    }

    /**
     * 目安を超えている項目を、画面に出す注意にする。
     *
     * <p>推移を引数で受け取るのは、推移の排他や OSHI の実測と切り離し、境目（85% ちょうど・記録が
     * {@code CPU_WARNING_MINUTES} 件に満たない起動直後・容量が取れない保存先）を確かめられるようにするため。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     *
     * @param system 端末全体の値
     * @param recent 推移の新しい順（先頭が今回の記録）。CPU の注意は先頭から {@code CPU_WARNING_MINUTES} 件だけを見る
     * @return 注意。cpu・memory・swap・disk の順。無ければ空
     */
    static List<Warning> evaluateWarnings(SystemUsage system, List<ResourceHistoryPoint> recent) {
        List<Warning> warnings = new ArrayList<>();
        boolean cpuHigh = recent.size() >= CPU_WARNING_MINUTES && recent.stream().limit(CPU_WARNING_MINUTES)
                .allMatch(p -> p.systemCpuPercent() != null && p.systemCpuPercent() > CPU_WARNING_PERCENT);
        if (cpuHigh) {
            warnings.add(new Warning("cpu", "CPU 使用率が %d 分以上 %.0f%% を超えています"
                    .formatted(CPU_WARNING_MINUTES, CPU_WARNING_PERCENT)));
        }
        if (system.memoryAvailableBytes() < system.memoryTotalBytes() * MEMORY_AVAILABLE_WARNING_RATIO) {
            warnings.add(new Warning("memory", "空きメモリが %.0f%% を下回っています（%s / %s）".formatted(
                    MEMORY_AVAILABLE_WARNING_RATIO * 100,
                    gigabytes(system.memoryAvailableBytes()), gigabytes(system.memoryTotalBytes()))));
        }
        if (system.swapTotalBytes() > 0 && system.swapUsedBytes() > system.swapTotalBytes() * SWAP_USED_WARNING_RATIO) {
            warnings.add(new Warning("swap", "スワップの使用が %.0f%% を超えています（%s / %s）".formatted(
                    SWAP_USED_WARNING_RATIO * 100,
                    gigabytes(system.swapUsedBytes()), gigabytes(system.swapTotalBytes()))));
        }
        if (system.diskTotalBytes() != null && system.diskFreeBytes() != null
                && system.diskFreeBytes() < system.diskTotalBytes() * DISK_FREE_WARNING_RATIO) {
            warnings.add(new Warning("disk", "録画の保存先の空きが %.0f%% を下回っています（%s / %s）".formatted(
                    DISK_FREE_WARNING_RATIO * 100,
                    gigabytes(system.diskFreeBytes()), gigabytes(system.diskTotalBytes()))));
        }
        return warnings;
    }

    private static String gigabytes(long bytes) {
        return "%.1f GB".formatted(bytes / 1e9);
    }

    /**
     * 分からない値（{@code null}）を 0 とみなすと合計が実際より小さく見えるので、1 つでも欠けたら合計も {@code null}。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     */
    static Double add(Double total, Double value) {
        return total == null || value == null ? null : total + value;
    }

    /**
     * 差を取るために、前回の記録の時点で残しておく値。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     */
    record Baseline(long timeMillis, long[] cpuTicks, long receivedBytes, long sentBytes,
                    Map<Integer, OSProcess> processes) {}

    private record Measurement(ResourceSnapshotResponse response, Baseline baseline) {}

    /**
     * プロセスの CPU 使用率を、前回の記録の時点からの CPU 時間の差で求める。
     *
     * <p>OSHI の {@code getProcessCpuLoadBetweenTicks} は 1 コアを 100% とした値なので使わず、
     * 経過時間とコア数で割って端末全体を 100% とした値にそろえる。前回の記録の後に始まったプロセスは
     * 起動からの累計で割る（その期間まるごとが差分の窓に収まっているため）。
     * 次回の差分計算のため、計測したプロセスを {@code tracked} に残す。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     */
    record CpuMeter(Baseline prior, boolean hasWindow, long windowMillis, int cores,
                    Map<Integer, OSProcess> tracked) {
        Double percent(OSProcess process) {
            tracked.put(process.getProcessID(), process);
            if (!hasWindow) return null;
            long cpuMillis = process.getKernelTime() + process.getUserTime();
            OSProcess before = prior.processes().get(process.getProcessID());
            long elapsed;
            if (before != null && before.getStartTime() == process.getStartTime()) {
                cpuMillis -= before.getKernelTime() + before.getUserTime();
                elapsed = windowMillis;
            } else {
                elapsed = Math.min(process.getUpTime(), windowMillis);
            }
            return elapsed <= 0 ? null : 100.0 * cpuMillis / elapsed / cores;
        }
    }
}
