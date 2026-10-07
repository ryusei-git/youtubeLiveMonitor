package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.SystemProcessesResponse;
import com.example.monitor.dto.SystemProcessesResponse.AppState;
import com.example.monitor.dto.SystemProcessesResponse.ProcessRow;
import com.example.monitor.dto.SystemProcessesResponse.RecordingInfo;
import com.example.monitor.dto.SystemProcessesResponse.Role;
import com.example.monitor.dto.SystemProcessesResponse.ServiceStatus;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.HubAppRegistry.HubApp;
import com.example.monitor.service.ResourceMonitorService.ProcessCpu;
import com.example.monitor.util.RecordingPathUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.software.os.InternetProtocolStats.IPConnection;
import oshi.software.os.InternetProtocolStats.TcpState;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 端末の状態の画面（{@code /system.html}）に出す、実行ユーザーのプロセスの一覧とサービスの
 * 状態を作る。
 *
 * <p><b>画面が読みに来たときだけ作り、10 秒使い回す。</b>全プロセスの列挙は重い（#201）ので、
 * 1 分ごとの記録（{@code ResourceMonitorService.record()}）には入れない。CPU 使用率だけは
 * 2 回の計測の差でしか出せないので、1 分ごとの記録が測ったプロセスごとの値
 * （{@link ResourceMonitorService#processCpu()}）を使う。
 *
 * <p><b>どのサービスのプロセスかは、上の規則から先に当たったものにする。</b>子孫には親と同じ
 * サービスを付ける。
 * <ol>
 *   <li>アプリ自身とその子孫（耳キスの検出・詰め替えの ffmpeg、録画の yt-dlp など）</li>
 *   <li>録画中の yt-dlp のうち、アプリの子孫でないもの（再起動の前から動き続けている録画）と
 *       その子孫</li>
 *   <li>確認用インスタンスとその子孫。サービスに数えない（枠の「サービス」の範囲とそろえる）</li>
 *   <li>作業フォルダーが登録したサービス（{@link HubAppRegistry}）のフォルダーの中にあるものと
 *       その子孫</li>
 * </ol>
 *
 * <p>CLI では作らない。依存する {@link ResourceMonitorService}・{@link HubAppRegistry} が CLI では
 * 作られないため。
 */
@Service
@Profile("!cli")
@RequiredArgsConstructor
@Slf4j
public class SystemProcessService {

    /** 一覧を使い回す時間（ミリ秒）。画面は 10 秒ごとに読み直すので、それより短くは作り直さない。 */
    private static final long CACHE_MILLIS = 10_000;

    /** このサービス自身の名前。左メニューの先頭と、アプリのプロセスの service に出す。 */
    private static final String SELF_NAME = "YouTube Live Monitor";

    /** このサービス自身のトップ画面。 */
    private static final String SELF_URL = "/index.html";

    /** Linux のプロセス名（{@code /proc/<pid>/status} の Name）は、この文字数で切れる。 */
    private static final int TRUNCATED_NAME_LENGTH = 15;

    /** 確認用インスタンス（{@code bin/sandbox.sh}・{@code bin/preview.sh}）だけが渡す引数。 */
    private static final String SANDBOX_ARGUMENT = "-Dmonitor.scheduling.enabled=false";

    /**
     * 端末の画面やセッションを支えるプロセスの名前の始まり。止めると画面のセッションごと落ちる。
     *
     * <p>実際に gnome-shell の子に claude-desktop や bash が入っており、止めるとそこで動いている
     * アプリもすべて止まる。名前で守るのは、「止めると危ない」という警告を出すだけでは、同じ黄色の
     * 警告が付くほかのアプリと見分けが付かず、うっかり選べてしまうため。
     */
    private static final List<String> PROTECTED_NAME_PREFIXES = List.of(
            "systemd", "(sd-pam)", "dbus-", "gnome-shell", "gnome-session-", "gdm-", "Xwayland", "pipewire",
            "wireplumber");

    /** アプリ自身の止められない理由。 */
    static final String LOCKED_SELF = "この画面を動かしている YouTube Live Monitor です。"
            + "停止するとこの画面も使えなくなるため、ここからは停止できません。";

    /** アプリの祖先の止められない理由。 */
    static final String LOCKED_SELF_ANCESTOR = "停止すると、この画面を動かしている YouTube Live Monitor も"
            + "一緒に止まるため、ここからは停止できません。";

    /** 端末の画面やセッションを支えるプロセスの止められない理由。 */
    static final String LOCKED_PROTECTED = "端末の画面やセッションを支えるプロセスです。"
            + "停止すると画面のセッションが終わり、そこで動いているアプリもすべて止まるため、"
            + "ここからは停止できません。";

    /** 端末の画面やセッションを支えるプロセスの祖先の止められない理由。 */
    static final String LOCKED_PROTECTED_ANCESTOR = "停止すると、端末の画面やセッションを支えるプロセスも"
            + "一緒に止まるため、ここからは停止できません。";

    private final HubAppRegistry hubAppRegistry;
    private final ResourceMonitorService resourceMonitorService;
    private final RecordingRepository recordingRepository;
    private final MonitorProperties monitorProperties;

    private final SystemInfo systemInfo = new SystemInfo();

    /** 直前に作った一覧。まだ作っていなければ {@code null}。 */
    private SystemProcessesResponse cached;

    /** {@link #cached} を作った時点の {@link System#nanoTime()}。時計を合わせ直しても狂わない。 */
    private long cachedAtNanos;

    /**
     * 実行ユーザーのプロセスの一覧とサービスの状態を返す。前回作ってから 10 秒以内なら、それを返す。
     *
     * <p>1 回作ると OSHI が約 16MiB を確保する（実行ユーザーの約 135 件）。画面を開いている間
     * 10 秒ごとに読みに来ても、複数のタブ・端末で同じ値を使い、列挙は 10 秒に 1 回にする
     * （synchronized はそのため）。1 分ごとの記録に入れないのは #201 と同じ理由（画面を開いて
     * いなくても確保が続く）。
     *
     * @return プロセスの一覧とサービスの状態（最大 10 秒古い）
     */
    public synchronized SystemProcessesResponse processes() {
        long now = System.nanoTime();
        if (cached == null || now - cachedAtNanos > Duration.ofMillis(CACHE_MILLIS).toNanos()) {
            cached = measure();
            cachedAtNanos = now;
        }
        return cached;
    }

    private SystemProcessesResponse measure() {
        OperatingSystem os = systemInfo.getOperatingSystem();

        // 実行ユーザーの PID を ProcessHandle で絞ってから OSHI に聞く。OSHI の全件の getProcesses()
        // や Predicate 付きは、全プロセスの OSProcess を作ってから絞るので約 35MiB を確保する。
        // OSHI の getUser() は呼ぶたびに getent を起動するので使わない
        Optional<String> user = ProcessHandle.current().info().user();
        List<Integer> pids = ProcessHandle.allProcesses()
                .filter(process -> user.isPresent() && process.info().user().equals(user))
                .map(process -> (int) process.pid())
                .toList();
        List<OSProcess> processes = os.getProcesses(pids).stream()
                .sorted(Comparator.comparingInt(OSProcess::getProcessID))
                .toList();
        Map<Integer, List<OSProcess>> childrenByParent = processes.stream()
                .collect(Collectors.groupingBy(OSProcess::getParentProcessID));
        // 作業フォルダーは呼ぶたびに /proc を読むので、1 回だけ読む。読めなければ空文字が返る
        Map<Integer, String> workingDirectories = new HashMap<>();
        for (OSProcess process : processes) {
            workingDirectories.put(process.getProcessID(), blankToNull(process.getCurrentWorkingDirectory()));
        }

        // 録画中の印は、どのサービスかの判定とは別に先に付ける。アプリが起動した録画の yt-dlp は
        // アプリの子孫なので、サービスの規則の後に見ると印が付かず、停止の API の「録画中は断る」を
        // すり抜けて PID を直接止め、録り直しが走る（helperUsages が録画を先に数えて除くのと同じ）
        Path recordingRoot = Path.of(monitorProperties.recording().directory()).toAbsolutePath().normalize();
        Map<String, Recording> recordings = new HashMap<>();
        for (Recording recording : recordingRepository.findWithChannelByStatus(RecordingStatus.RECORDING)) {
            recordings.putIfAbsent(recording.getVideoId(), recording);
        }
        Map<Integer, RecordingInfo> recordingOf = new HashMap<>();
        List<Integer> recorders = new ArrayList<>();
        for (OSProcess process : processes) {
            String videoId = ResourceMonitorService.recordingVideoId(process, recordingRoot);
            Recording recording = videoId == null ? null : recordings.get(videoId);
            if (recording == null) continue;
            recorders.add(process.getProcessID());
            RecordingInfo info = new RecordingInfo(recording.getId(),
                    recording.getChannel() == null ? null : recording.getChannel().getChannelName(),
                    recording.getVideoTitle(), recording.getStartedAt(), fileBytes(recordingRoot, recording));
            for (int pid : withDescendants(process.getProcessID(), childrenByParent)) {
                recordingOf.putIfAbsent(pid, info);
            }
        }

        // どのサービスか。値が null の「どれでもない」と「まだ決めていない」を分けるため、
        // containsKey で見る
        int selfPid = (int) ProcessHandle.current().pid();
        Map<Integer, String> serviceOf = new HashMap<>();
        Map<Integer, Role> roles = new HashMap<>();
        roles.put(selfPid, Role.SELF);
        claim(selfPid, SELF_NAME, childrenByParent, serviceOf);
        for (int pid : recorders) {
            claim(pid, SELF_NAME, childrenByParent, serviceOf);
        }
        for (OSProcess process : processes) {
            if (!serviceOf.containsKey(process.getProcessID()) && isSandbox(process.getArguments())) {
                roles.put(process.getProcessID(), Role.SANDBOX);
                claim(process.getProcessID(), null, childrenByParent, serviceOf);
            }
        }
        for (OSProcess process : processes) {
            if (serviceOf.containsKey(process.getProcessID())) continue;
            hubApp(workingDirectories.get(process.getProcessID()))
                    .ifPresent(app -> claim(process.getProcessID(), app.name(), childrenByParent, serviceOf));
        }

        // 一覧に無い root の祖先（PID 1 など）も入れる。同じ PID に戻ったら止める
        Set<Integer> selfAncestors = new HashSet<>();
        Optional<ProcessHandle> parent = ProcessHandle.current().parent();
        while (parent.isPresent() && selfAncestors.add((int) parent.get().pid())) {
            parent = parent.get().parent();
        }
        Map<Integer, String> lockedReasons = lockedReasons(processes, selfPid, selfAncestors);
        Map<Integer, List<Integer>> ports = listeningPorts(os);
        Map<Long, ProcessCpu> cpu = resourceMonitorService.processCpu();

        List<ProcessRow> rows = new ArrayList<>();
        for (OSProcess process : processes) {
            int pid = process.getProcessID();
            // PID は使い回されるので、1 分ごとの記録と起動時刻も同じときだけ同じプロセスの値とみなす
            ProcessCpu measured = cpu.get((long) pid);
            Double cpuPercent = measured != null && measured.startTime() == process.getStartTime()
                    ? measured.percent() : null;
            rows.add(new ProcessRow(pid, process.getParentProcessID(), process.getStartTime(),
                    displayName(process.getName(), process.getPath(), process.getArguments()),
                    blankToNull(process.getCommandLine()), workingDirectories.get(pid), cpuPercent,
                    process.getResidentMemory(), process.getUpTime() / 1000, ports.getOrDefault(pid, List.of()),
                    serviceOf.get(pid), roles.get(pid), recordingOf.get(pid), lockedReasons.get(pid)));
        }

        boolean workingDirectoryReadable = workingDirectories.values().stream().anyMatch(Objects::nonNull);
        List<ServiceStatus> services = new ArrayList<>();
        services.add(new ServiceStatus(SELF_NAME, SELF_URL, AppState.RUNNING, null, true));
        for (HubApp app : hubAppRegistry.apps()) {
            AppState state = serviceOf.containsValue(app.name()) ? AppState.RUNNING
                    : workingDirectoryReadable ? AppState.STOPPED : AppState.UNKNOWN;
            services.add(new ServiceStatus(app.name(), app.url(), state, app.launch(), false));
        }

        return new SystemProcessesResponse(LocalDateTime.now(), os.getNetworkParams().getHostName(),
                os.getSystemUptime(), user.orElse(null),
                systemInfo.getHardware().getProcessor().getLogicalProcessorCount(), services, rows);
    }

    /**
     * 画面に出すプロセス名を返す。
     *
     * <p>Linux のプロセス名は 15 文字で切れる（{@code gnome-remote-desktop-daemon} が
     * {@code gnome-remote-de} になる）ので、ちょうど 15 文字のときだけ、実行ファイルのパス、それが
     * 使えなければ引数の先頭のファイル名のうち、切れた名前で始まってそれより長いものに置き換える。
     * パスを最初から使わないのは、yt-dlp のようなスクリプトではパスが {@code python} になり、
     * かえって分からなくなるため。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     *
     * @param name OSHI のプロセス名
     * @param path 実行ファイルのパス。読めなければ {@code null} か空
     * @param args 引数（先頭は実行ファイル）。読めなければ {@code null} か空
     * @return 画面に出す名前
     */
    static String displayName(String name, String path, List<String> args) {
        if (name == null || name.length() != TRUNCATED_NAME_LENGTH) return name;
        String first = args == null || args.isEmpty() ? null : args.getFirst();
        return Stream.of(fileName(path), fileName(first))
                .filter(candidate -> candidate.startsWith(name) && candidate.length() > name.length())
                .findFirst()
                .orElse(name);
    }

    /**
     * 確認用インスタンス（{@code bin/sandbox.sh}・{@code bin/preview.sh}）の java かを、起動の
     * しかたで見分ける。
     *
     * <p>jar の名前や作業フォルダーで見分けないのは、本番も jar を相対パスで渡していて区別が
     * 付かず、preview の jar は名前が古いまま残ることがあるため。監視を止める引数は確認用
     * インスタンスだけが渡す。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     *
     * @param args 引数（先頭は実行ファイル）。読めなければ {@code null} か空
     * @return 確認用インスタンスの java なら {@code true}
     */
    static boolean isSandbox(List<String> args) {
        return args != null && !args.isEmpty() && fileName(args.getFirst()).startsWith("java")
                && args.contains(SANDBOX_ARGUMENT);
    }

    /**
     * 画面から止めてはいけないプロセスと、その理由を返す。
     *
     * <p>アプリ自身とその祖先は、止めるとこの画面も使えなくなる。端末の画面やセッションを支える
     * プロセス（{@code PROTECTED_NAME_PREFIXES}）とその祖先は、止めると画面のセッションごと落ちる。
     * 2 つ当たるときは、アプリ自身・アプリの祖先の理由を残す（この画面が使えなくなることの方を
     * 先に伝える）。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     *
     * @param processes     実行ユーザーのプロセス
     * @param selfPid       アプリ（この JVM）のプロセス ID
     * @param selfAncestors アプリの祖先のプロセス ID（一覧に無い root のものも入る）
     * @return プロセス ID → 止められない理由。止めてよいプロセスは載せない
     */
    static Map<Integer, String> lockedReasons(List<OSProcess> processes, int selfPid, Set<Integer> selfAncestors) {
        Map<Integer, OSProcess> byPid = new HashMap<>();
        Map<Integer, String> reasons = new HashMap<>();
        for (OSProcess process : processes) {
            int pid = process.getProcessID();
            byPid.put(pid, process);
            if (pid == selfPid) {
                reasons.put(pid, LOCKED_SELF);
            } else if (selfAncestors.contains(pid)) {
                reasons.put(pid, LOCKED_SELF_ANCESTOR);
            }
        }
        List<OSProcess> protectedProcesses = processes.stream()
                .filter(process -> {
                    String name = displayName(process.getName(), process.getPath(), process.getArguments());
                    return name != null && PROTECTED_NAME_PREFIXES.stream().anyMatch(name::startsWith);
                })
                .toList();
        // 守るプロセスの理由を先に付ける（祖先の理由より、守るプロセスそのものの理由を優先する）
        for (OSProcess process : protectedProcesses) {
            reasons.putIfAbsent(process.getProcessID(), LOCKED_PROTECTED);
        }
        for (OSProcess process : protectedProcesses) {
            Set<Integer> seen = new HashSet<>();
            for (OSProcess parent = byPid.get(process.getParentProcessID());
                 parent != null && seen.add(parent.getProcessID());
                 parent = byPid.get(parent.getParentProcessID())) {
                reasons.putIfAbsent(parent.getProcessID(), LOCKED_PROTECTED_ANCESTOR);
            }
        }
        return reasons;
    }

    /**
     * 待ち受けている TCP のポートを、プロセスごとに返す。
     *
     * <p>tcp4 と tcp6 で同じポートを待ち受けるプロセスが多いので、重複を除いて昇順にする。
     * 読めなくてもプロセスの一覧は出せるので、ポートを空にして続ける。
     */
    private static Map<Integer, List<Integer>> listeningPorts(OperatingSystem os) {
        Map<Integer, Set<Integer>> ports = new HashMap<>();
        try {
            for (IPConnection connection : os.getInternetProtocolStats().getConnections()) {
                if (connection.getState() == TcpState.LISTEN && connection.getowningProcessId() >= 0) {
                    ports.computeIfAbsent(connection.getowningProcessId(), pid -> new TreeSet<>())
                            .add(connection.getLocalPort());
                }
            }
        } catch (RuntimeException e) {
            log.warn("待ち受けているポートを読めませんでした", e);
            return Map.of();
        }
        return ports.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    /**
     * 録画中の動画の出力ファイル（断片を含む）の大きさの合計を返す。
     *
     * <p>録画中は完成ファイルがまだ無く、断片（{@code .f137.mp4}・{@code -FragN} など）に書いて
     * いるので、{@code RecordingActivity.lastModified} と同じく、録画フォルダーの中でファイル名の
     * 先頭が動画 ID のものを足す。走査の途中で消えた断片（yt-dlp は結合のたびに消す）は 0 と数える。
     */
    private static long fileBytes(Path recordingRoot, Recording recording) {
        try (Stream<Path> files = Files.list(recordingRoot.resolve(recording.getFilePath()).getParent())) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> RecordingPathUtils.videoId(file).equals(recording.getVideoId()))
                    .mapToLong(SystemProcessService::sizeOrZero)
                    .sum();
        } catch (IOException | UncheckedIOException | InvalidPathException e) {
            return 0;
        }
    }

    private static long sizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 作業フォルダーが登録したサービスのフォルダーの中なら、そのサービスを返す。 */
    private Optional<HubApp> hubApp(String workingDirectory) {
        if (workingDirectory == null) return Optional.empty();
        try {
            return hubAppRegistry.appFor(Path.of(workingDirectory));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * プロセスとその子孫を、まだどのサービスか決まっていなければ、そのサービスのものにする。
     * 先の規則で決まったものは上書きしない（規則の順を守るため）。
     */
    private static void claim(int pid, String service, Map<Integer, List<OSProcess>> childrenByParent,
                              Map<Integer, String> serviceOf) {
        for (int member : withDescendants(pid, childrenByParent)) {
            if (!serviceOf.containsKey(member)) {
                serviceOf.put(member, service);
            }
        }
    }

    /** プロセスとその子孫の PID（親 PID の表は一覧の中で組んだもの。循環した表でも止まる）。 */
    private static Set<Integer> withDescendants(int pid, Map<Integer, List<OSProcess>> childrenByParent) {
        Set<Integer> found = new HashSet<>();
        Deque<Integer> pending = new ArrayDeque<>(List.of(pid));
        while (!pending.isEmpty()) {
            int current = pending.pop();
            if (!found.add(current)) continue;
            for (OSProcess child : childrenByParent.getOrDefault(current, List.of())) {
                pending.push(child.getProcessID());
            }
        }
        return found;
    }

    /** OSHI は読めない値を空文字で返すので、「読めなかった」と分かるよう {@code null} にする。 */
    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** パスの最後の要素。Windows の区切りも見る。{@code null} や空なら空文字。 */
    private static String fileName(String path) {
        if (path == null) return "";
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }
}
