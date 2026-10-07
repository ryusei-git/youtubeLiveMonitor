package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.SystemProcessesResponse;
import com.example.monitor.dto.SystemProcessesResponse.AppState;
import com.example.monitor.dto.SystemProcessesResponse.ProcessRow;
import com.example.monitor.dto.SystemProcessesResponse.RecordingInfo;
import com.example.monitor.dto.SystemProcessesResponse.Role;
import com.example.monitor.dto.SystemProcessesResponse.ServiceStatus;
import com.example.monitor.dto.SystemProcessesResponse.StopJob;
import com.example.monitor.dto.SystemProcessesResponse.StopState;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.HubAppRegistry.HubApp;
import com.example.monitor.service.ResourceMonitorService.ProcessCpu;
import com.example.monitor.util.ProcessTermination;
import com.example.monitor.util.ProcessTermination.TreeResult;
import com.example.monitor.util.RecordingPathUtils;
import com.example.monitor.util.RequestContext;
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
import java.time.Instant;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
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
 * <p><b>どのサービスのプロセスかは、次の規則のうち上にあるものから先に当てる。</b>子孫には親と
 * 同じサービスを付ける。
 * <ol>
 *   <li>アプリ自身とその子孫（耳キスの検出・詰め替えの ffmpeg、録画の yt-dlp など）</li>
 *   <li>録画中の yt-dlp のうち、アプリの子孫でないもの（再起動の前から動き続けている録画）と
 *       その子孫</li>
 *   <li>確認用インスタンスとその子孫。サービスに数えない（枠の「サービス」の範囲とそろえる）</li>
 *   <li>作業フォルダーが登録したサービス（{@link HubAppRegistry}）のフォルダーの中にあるものと
 *       その子孫</li>
 * </ol>
 *
 * <p><b>画面で選んだプロセスを子孫ごと止める（{@link #stop}）。</b>止めている途中と結果は、画面を
 * 読み直しても出せるよう、この一覧（{@link #processes()}）に載せる。止めた記録はメモリだけに持ち、
 * 後から追えるのは監査ログとアプリのログ。
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

    /** 選んだプロセスがもう無い（PID が別のプロセスに使い回された場合を含む）ときの断る理由。 */
    static final String REFUSED_GONE = "このプロセスはすでに終了しています。";

    /**
     * 録画のプロセスを断る理由。PID を直接止めると、{@code StreamRecorder} が「普通に終わった」と見て
     * 今の時点から録り直すので、録り直しを止める印を立てる録画の停止から止めてもらう。
     */
    static final String REFUSED_RECORDING = "録画のプロセスです。「録画を停止」から止めてください。";

    /**
     * SIGTERM の後、SIGKILL に切り替えるまで待つ時間。画面の文言「10 秒たっても残っていれば強制終了」と
     * そろえる（{@code DeviceDownloadService} の {@code TERMINATION_GRACE} と同じ値）。
     */
    private static final Duration STOP_GRACE = Duration.ofSeconds(10);

    /**
     * SIGKILL の後、終わるのを待つ時間。D 状態（I/O 待ちで止まらない）のプロセスは SIGKILL でも
     * 終わらないので、上限を置いて「強制終了しても残った」と出す。
     */
    private static final Duration KILL_WAIT = Duration.ofSeconds(5);

    /** 止め終わった記録を一覧に残す時間。画面を読み直しても結果を確かめられるようにする。 */
    private static final Duration FINISHED_KEEP = Duration.ofMinutes(10);

    /** 一覧に載せる停止の記録の上限。続けて止めても一覧が伸び続けないようにする。 */
    private static final int MAX_STOPS = 20;

    private final HubAppRegistry hubAppRegistry;
    private final ResourceMonitorService resourceMonitorService;
    private final RecordingRepository recordingRepository;
    private final MonitorProperties monitorProperties;
    private final AuditLogger auditLogger;

    private final SystemInfo systemInfo = new SystemInfo();

    /**
     * 停止の記録（番号 → 記録）。止めるスレッドがロックを取らずに「強制終了しています」へ書き換える
     * （画面の読み込みの一覧作りを待たない）ので、並行に読み書きできる表にする。
     */
    private final Map<Long, StopJob> stopJobs = new ConcurrentHashMap<>();

    /**
     * 止めている途中の木に入っているプロセス ID → その記録の番号。木の中の別のプロセスを選んで
     * 押されても 2 つ目を始めず、その記録を返すため。{@code this} のロックの中だけで読み書きする。
     */
    private final Map<Integer, Long> stoppingPids = new HashMap<>();

    /** 停止の記録の番号。 */
    private final AtomicLong stopIds = new AtomicLong();

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
     * <p>停止の状態（{@link ProcessRow#stopState}・{@link SystemProcessesResponse#stops}）は使い回さず、
     * 読むたびに今の記録を載せる。10 秒古い「停止しています」を出すと、止め終わったのに画面が
     * 変わらないため。
     *
     * @return プロセスの一覧とサービスの状態（プロセスは最大 10 秒古い）
     */
    public synchronized SystemProcessesResponse processes() {
        long now = System.nanoTime();
        if (cached == null || now - cachedAtNanos > Duration.ofMillis(CACHE_MILLIS).toNanos()) {
            cached = measure();
            cachedAtNanos = now;
        }
        LocalDateTime keepAfter = LocalDateTime.now().minus(FINISHED_KEEP);
        stopJobs.values().removeIf(job -> job.finishedAt() != null && job.finishedAt().isBefore(keepAfter));
        List<StopJob> stops = stopJobs.values().stream()
                .sorted(Comparator.comparingLong(StopJob::id).reversed())
                .toList();
        stops.stream().skip(MAX_STOPS).filter(job -> job.finishedAt() != null)
                .forEach(job -> stopJobs.remove(job.id()));
        List<ProcessRow> rows = cached.processes().stream()
                .map(row -> {
                    Long id = stoppingPids.get(row.pid());
                    StopJob job = id == null ? null : stopJobs.get(id);
                    return job == null ? row : withStopState(row, job.state());
                })
                .toList();
        return new SystemProcessesResponse(cached.measuredAt(), cached.hostName(), cached.uptimeSeconds(),
                cached.user(), cached.cores(), cached.services(), rows,
                stops.stream().limit(MAX_STOPS).toList());
    }

    /**
     * 停止の受け付けの結果。
     *
     * @param job   受け付けた停止の記録（すでに止めている途中なら、その記録）。断ったら {@code null}
     * @param error 断った理由（画面にそのまま出す）。受け付けたら {@code null}
     */
    public record StopResult(StopJob job, String error) {
    }

    /**
     * 画面で選んだプロセスを子孫ごと止める。SIGTERM を送り、10 秒たっても残れば SIGKILL を送る。
     * 止めるのは仮想スレッドで、ここは受け付けたらすぐ戻る（止め終わるまで最大 15 秒かかるため）。
     *
     * <p><b>一覧はキャッシュを使わずに作り直す。</b>10 秒古い一覧で「選んでよいか」を決めると、その間に
     * 始まった録画や、PID を使い回した別のプロセスを止めてしまう。さらに止める直前に、OS から引いた
     * プロセスの起動時刻が選んだときのものと同じかを確かめる（PID は使い回されるため）。
     *
     * <p><b>synchronized にしているのは、同じプロセスを 2 回押す・2 つのタブから押すことがあるため。</b>
     * 止めている途中の木に入っているプロセスなら、選んだ本人でも子でも、起動時刻を問わず、その記録を
     * 返すだけで 2 つ目は始めない。
     *
     * <p>録画のプロセス（子孫に録画を含むものも）は断る（{@link #REFUSED_RECORDING}）。止めたことも
     * 断ったことも、監査ログに残す。コマンドラインは秘密が入ることがあるので、ログには載せない。
     *
     * @param pid       選んだプロセスの ID
     * @param startTime 選んだときのプロセスの起動時刻（{@link ProcessRow#startTime}）
     * @return 受け付けた記録か、断った理由
     */
    public synchronized StopResult stop(int pid, long startTime) {
        Long running = stoppingPids.get(pid);
        if (running != null) {
            return new StopResult(stopJobs.get(running), null);
        }

        cached = measure();
        cachedAtNanos = System.nanoTime();
        List<ProcessRow> rows = cached.processes();
        ProcessRow row = rows.stream()
                .filter(candidate -> candidate.pid() == pid)
                .findFirst()
                .orElse(null);
        Set<Integer> treePids = withDescendants(pid,
                rows.stream().collect(Collectors.groupingBy(ProcessRow::parentPid)), ProcessRow::pid);
        boolean recordingInTree = rows.stream()
                .anyMatch(candidate -> treePids.contains(candidate.pid()) && candidate.recording() != null);
        String refused = refusal(row, startTime, recordingInTree);
        if (refused != null) {
            return refuse(pid, row, refused);
        }

        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        long handleStartTime = handle.flatMap(found -> found.info().startInstant())
                .map(Instant::toEpochMilli)
                .orElse(-1L);
        if (handleStartTime != startTime) {
            return refuse(pid, row, REFUSED_GONE);
        }
        // 子孫は止める前に 1 回だけ集める（ProcessTermination#terminateTree 参照）
        ProcessHandle root = handle.get();
        List<ProcessHandle> tree = Stream.concat(root.descendants(), Stream.of(root)).toList();
        if (tree.contains(ProcessHandle.current())) {
            // 祖先は lockedReason で断っているはずだが、念のため（止めるとこの画面も使えなくなる）
            return refuse(pid, row, LOCKED_SELF_ANCESTOR);
        }

        long id = stopIds.incrementAndGet();
        StopJob job = new StopJob(id, pid, startTime, row.name(), tree.size() - 1, StopState.STOPPING,
                LocalDateTime.now(), null, List.of());
        stopJobs.put(id, job);
        // 木が重なったときは先の記録を残す（後の記録が先に終わっても、先の記録の印を外さない）
        tree.forEach(member -> stoppingPids.putIfAbsent((int) member.pid(), id));
        auditLogger.recordByCurrentUser(AuditAction.PROCESS_STOP, AuditOutcome.SUCCESS, "PROCESS",
                String.valueOf(pid), "name=" + row.name() + ", children=" + job.children()
                        + ", service=" + (row.service() == null ? "none" : row.service()));
        log.info("画面の操作でプロセスを止めます: pid={}, name={}, children={}, 操作者={}",
                pid, row.name(), job.children(), RequestContext.currentUsername());
        Thread.ofVirtual().name("stop-process-" + pid).start(() -> terminate(job, tree));
        return new StopResult(job, null);
    }

    /**
     * 止めてはいけないプロセスなら、その理由を返す。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     *
     * @param row             作り直した一覧の、選んだプロセスの行。一覧に無ければ {@code null}
     *                        （ほかのユーザーのプロセスも一覧に載らないので、ここで断る）
     * @param startTime       選んだときのプロセスの起動時刻
     * @param recordingInTree 子孫に録画のプロセスがあるか
     * @return 断る理由。止めてよければ {@code null}
     */
    static String refusal(ProcessRow row, long startTime, boolean recordingInTree) {
        if (row == null || row.startTime() != startTime) return REFUSED_GONE;
        if (row.lockedReason() != null) return row.lockedReason();
        if (row.recording() != null || recordingInTree) return REFUSED_RECORDING;
        return null;
    }

    /** 断ったことを監査ログに残し、断った結果を返す。 */
    private StopResult refuse(int pid, ProcessRow row, String reason) {
        String code = REFUSED_GONE.equals(reason) ? "gone"
                : REFUSED_RECORDING.equals(reason) ? "recording" : "locked";
        auditLogger.recordByCurrentUser(AuditAction.PROCESS_STOP, AuditOutcome.FAILURE, "PROCESS",
                String.valueOf(pid), "name=" + (row == null ? "-" : row.name()) + ", reason=" + code);
        return new StopResult(null, reason);
    }

    /**
     * {@link #stop} が受け付けた木を止め、結果を記録する（仮想スレッドで動く）。
     *
     * @param job  止める記録
     * @param tree 止める前に集めた木
     */
    private void terminate(StopJob job, List<ProcessHandle> tree) {
        boolean forced;
        boolean interrupted = false;
        List<ProcessHandle> left;
        try {
            TreeResult result = ProcessTermination.terminateTree(tree, STOP_GRACE, KILL_WAIT,
                    () -> stopJobs.computeIfPresent(job.id(),
                            (id, current) -> withState(current, StopState.KILLING, null, List.of())));
            forced = result.forced();
            left = result.remaining();
        } catch (InterruptedException e) {
            // アプリの停止などで割り込まれた。全員に SIGKILL を送り済み
            Thread.currentThread().interrupt();
            forced = true;
            interrupted = true;
            left = tree.stream().filter(ProcessHandle::isAlive).toList();
        }
        // ProcessHandle#isAlive はゾンビ（終わって親の回収を待つだけ）も生きているとみなすので、
        // OS に聞き直す
        OperatingSystem os = systemInfo.getOperatingSystem();
        List<Integer> remaining = left.stream()
                .map(member -> (int) member.pid())
                .filter(member -> {
                    OSProcess process = os.getProcess(member);
                    return process != null && process.getState() != OSProcess.State.ZOMBIE;
                })
                .toList();
        StopState state = interrupted || !remaining.isEmpty() ? StopState.FAILED
                : forced ? StopState.KILLED : StopState.STOPPED;
        finish(job, state, remaining, tree);
    }

    /**
     * 止め終わった記録を書き、止めている途中の印を外し、一覧を作り直させる（止めたプロセスが
     * 10 秒古い一覧に残らないように）。{@link #processes()}・{@link #stop} と同じロックで行う。
     */
    private synchronized void finish(StopJob job, StopState state, List<Integer> remaining,
                                     List<ProcessHandle> tree) {
        stopJobs.put(job.id(), withState(job, state, LocalDateTime.now(), remaining));
        tree.forEach(member -> stoppingPids.remove((int) member.pid(), job.id()));
        cached = null;
        if (state == StopState.FAILED) {
            log.warn("強制終了しても残っているプロセスがあります: pid={}, remaining={}",
                    job.pid(), remaining);
        } else {
            log.info("画面の操作で止めたプロセスが終わりました: pid={}, name={}, state={}",
                    job.pid(), job.name(), state);
        }
    }

    private static StopJob withState(StopJob job, StopState state, LocalDateTime finishedAt,
                                     List<Integer> remaining) {
        return new StopJob(job.id(), job.pid(), job.startTime(), job.name(), job.children(), state,
                job.startedAt(), finishedAt, remaining);
    }

    private static ProcessRow withStopState(ProcessRow row, StopState state) {
        return new ProcessRow(row.pid(), row.parentPid(), row.startTime(), row.name(), row.commandLine(),
                row.workingDirectory(), row.cpuPercent(), row.memoryBytes(), row.upSeconds(), row.ports(),
                row.service(), row.role(), row.recording(), row.lockedReason(), state);
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
            for (int pid : withDescendants(process.getProcessID(), childrenByParent, OSProcess::getProcessID)) {
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
                    serviceOf.get(pid), roles.get(pid), recordingOf.get(pid), lockedReasons.get(pid), null));
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
                systemInfo.getHardware().getProcessor().getLogicalProcessorCount(), services, rows, List.of());
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
        for (int member : withDescendants(pid, childrenByParent, OSProcess::getProcessID)) {
            if (!serviceOf.containsKey(member)) {
                serviceOf.put(member, service);
            }
        }
    }

    /**
     * プロセスとその子孫の PID（親 PID の表は一覧の中で組んだもの。循環した表でも止まる）。
     * OSHI のプロセスと一覧の行の両方からたどるので、表の要素から PID を引く関数を受け取る。
     */
    private static <T> Set<Integer> withDescendants(int pid, Map<Integer, List<T>> childrenByParent,
                                                    ToIntFunction<T> pidOf) {
        Set<Integer> found = new HashSet<>();
        Deque<Integer> pending = new ArrayDeque<>(List.of(pid));
        while (!pending.isEmpty()) {
            int current = pending.pop();
            if (!found.add(current)) continue;
            for (T child : childrenByParent.getOrDefault(current, List.of())) {
                pending.push(pidOf.applyAsInt(child));
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
