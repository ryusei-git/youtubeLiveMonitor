package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.DeviceDownloadResponse;
import com.example.monitor.dto.DeviceDownloadResponse.Status;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.exception.DeviceDownloadInProgressException;
import com.example.monitor.exception.DeviceDownloadNotFoundException;
import com.example.monitor.exception.InsufficientDiskSpaceException;
import com.example.monitor.exception.LiveStreamDownloadRejectedException;
import com.example.monitor.exception.VideoAlreadyDownloadedException;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import com.example.monitor.util.DiskSpaceUtils;
import com.example.monitor.util.ProcessTermination;
import com.example.monitor.util.YtDlpLogFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 利用者が動画を「自分の端末に保存」するための一時取得（#451）。
 *
 * <h2>サービスの録画に入れない</h2>
 * 「サービスに保存」（{@link VideoDownloadService}）は録画履歴を作り、全員のアーカイブに出る。
 * こちらは取得した本人だけが受け取れればよいので、録画履歴も {@code recordings/} も使わず、
 * {@code data/device-downloads/<仕事ID>/} に落として、期限（既定 24 時間）が来たら消す。
 * 仕事 ID ごとのフォルダーにしているのは、2 人が同じ動画を同時に取得しても出力先が重ならないようにするため
 * （そのため {@link ActiveVideoJobs} の動画 ID の予約は使わない。予約すると、端末への取得中は
 * 同じ動画の自動録画・サービスへの保存まで止まってしまう）。
 *
 * <h2>仕事の状態はメモリだけに持つ</h2>
 * 再起動で消えてよい（Issue の決定）。消えた仕事のフォルダーは、{@link #purgeExpired()} が
 * フォルダーの更新日時から期限を判断して消す。再起動しても {@code yt-dlp} は出力をファイルへ書いているので
 * 動き続けるため（{@code docs/pitfalls.md}「外部プロセスの出力を JVM へのパイプにすると…」）、
 * 消す前にそのフォルダーへ書いているプロセスを止める。探すときは仕事 ID（UUID）を含むパスで照合するので、
 * 動画 ID で探したときのように別の録画を巻き込むことはない（同「録画中かの判定は…誤検知する」）。
 *
 * <h2>ディスク使用量の表示には含めない</h2>
 * 使用量の表示（{@code RecordingFileService}）は {@code recordings/} の実ファイルを走査している。
 * 一時ファイルは最長でも期限で消えるうえ、ボリュームの空き容量の表示には実際の減りとして現れるので、
 * 走査の対象を増やしてまで別に数えない。
 */
@Service
@Profile("!cli")
@RequiredArgsConstructor
@Slf4j
public class DeviceDownloadService {

    /** 期限切れで止めるとき、SIGTERM から SIGKILL へ切り替えるまで待つ時間。 */
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(10);

    private final MonitorProperties monitorProperties;
    private final StreamPlatformRegistry streamPlatformRegistry;
    private final VideoSourceProbe videoSourceProbe;
    private final ProcessLauncher processLauncher;
    private final RecordingSalvager recordingSalvager;
    private final RecordingRepository recordingRepository;
    private final CurrentAppUser currentAppUser;
    private final AuditLogger auditLogger;

    /** {@code yt-dlp} の {@code --js-runtimes}。{@link VideoDownloadService} と同じ設定を使う。 */
    @Value("${monitor.recording.js-runtime:}")
    private String jsRuntime = "";

    /** 始めるのに必要な空き容量（GB）。録画・サービスへの保存と同じしきい値（#315）。 */
    @Value("${monitor.recording.min-free-gb:20}")
    private long minFreeGb = 0;

    /** 一時ファイルの置き場所。H2 と同じ {@code data/} の下（録画の走査・削除に混ざらないように）。 */
    @Value("${monitor.device-download.directory:data/device-downloads}")
    private String directory = "data/device-downloads";

    /** 完成（完成していなければ作成）から消すまでの時間。確認のために短く上書きできるよう設定にしている。 */
    @Value("${monitor.device-download.retention:PT24H}")
    private Duration retention = Duration.ofHours(24);

    /** 仕事 ID → 仕事。 */
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /**
     * 「端末に保存」を受け付ける。プロセスを起動したらすぐに返る。
     *
     * <p>既にサービスに完成した録画があれば取り直さず、そのファイルの場所を {@code READY} で返す。
     * サービスの録画が {@code PARTIAL}（途中まで）・{@code FAILED} のときは渡さず、改めて一時取得する。取り直せば最後まで
     * 取れることがあるため（一時取得でも途中までしか取れなかったときは、途中までだと知らせたうえで渡す）。
     *
     * @param rawUrl 利用者が入力した動画の URL
     * @return 受け付けた内容
     * @throws IllegalArgumentException           URL が空・対応していない・情報を取得できない場合（400）
     * @throws LiveStreamDownloadRejectedException 配信中・待機所の URL の場合（400）
     * @throws DeviceDownloadInProgressException  この利用者が既に 1 件取得中の場合（409）
     * @throws VideoAlreadyDownloadedException    サービスが同じ動画を録画・保存中の場合（409）
     * @throws InsufficientDiskSpaceException     空き容量がしきい値を下回る場合（503）
     */
    public DeviceDownloadResponse start(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("動画のURLを入力してください");
        }
        AppUser user = currentAppUser.require();
        if (DiskSpaceUtils.isBelow(Path.of(directory), minFreeGb)) {
            throw new InsufficientDiskSpaceException();
        }
        String url = rawUrl.trim();
        streamPlatformRegistry.findByUrl(url);

        // 情報の取得（数秒かかる）の前に枠を取る。後にすると、連打した 2 件がどちらも通る
        Job job = reserve(user.getId());
        boolean started = false;
        try {
            VideoSource source = videoSourceProbe.probe(url)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "動画の情報を取得できませんでした。URLを確認してください: " + url));
            if (source.isLiveOrUpcoming()) {
                throw new LiveStreamDownloadRejectedException(source.videoId());
            }
            job.videoId = source.videoId();
            job.title = source.title() == null ? source.videoId() : source.title();

            Optional<Recording> existing = recordingRepository.findFirstByVideoId(job.videoId);
            if (existing.isPresent() && existing.get().getStatus() == RecordingStatus.RECORDING) {
                throw new VideoAlreadyDownloadedException(job.videoId);
            }
            if (existing.isPresent() && existing.get().getStatus() == RecordingStatus.COMPLETED) {
                Recording recording = existing.get();
                audit(user, "RECORDING", String.valueOf(recording.getId()), url, job.videoId);
                return new DeviceDownloadResponse(null, Status.READY, recording.getVideoTitle(),
                        recording.getId(), "/recordings/" + recording.getFilePath());
            }

            launch(job, url);
            started = true;
            audit(user, "DEVICE_DOWNLOAD", job.id, url, job.videoId);
            return job.toResponse();
        } finally {
            if (!started) {
                jobs.remove(job.id);
            }
        }
    }

    /**
     * 仕事の状態を返す。
     *
     * @param jobId 仕事 ID
     * @return 状態
     * @throws DeviceDownloadNotFoundException 無い、または他人の仕事の場合（404）
     */
    public DeviceDownloadResponse status(String jobId) {
        return ownedJob(jobId).toResponse();
    }

    /**
     * 受け取れる状態のファイル（完成品か途中までのもの）を返す。
     *
     * @param jobId 仕事 ID
     * @return ファイルと、保存するときのファイル名
     * @throws DeviceDownloadNotFoundException 無い・他人の仕事・取得中・失敗した場合（404）
     */
    public ReadyFile readyFile(String jobId) {
        Job job = ownedJob(jobId);
        if (!job.status.hasFile() || !Files.isRegularFile(job.outputFile())) {
            throw new DeviceDownloadNotFoundException();
        }
        return new ReadyFile(job.outputFile(), downloadName(job.title, job.videoId));
    }

    /**
     * 期限を過ぎた一時ファイルを消す。取得中のプロセスは止めてから消す。
     *
     * <p>メモリにある仕事は完成（未完成なら作成）の時刻で、メモリに無いフォルダー（再起動前の仕事）は
     * 更新日時で判断する。フォルダーの更新日時は、中のファイルが作られ・結合されるたびに進むので、
     * 概ね「完成した時刻」に当たる。
     */
    @Scheduled(fixedDelayString = "${monitor.device-download.cleanup-interval:PT10M}")
    public void purgeExpired() {
        Instant deadline = Instant.now().minus(retention);
        for (Job job : jobs.values()) {
            Instant base = job.completedAt != null ? job.completedAt : job.createdAt;
            if (base.isBefore(deadline) && jobs.remove(job.id, job)) {
                if (job.process != null && job.process.isAlive()) {
                    terminate(job.process.toHandle());
                }
                delete(job.directory());
            }
        }

        Path root = Path.of(directory);
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> orphans;
        try (Stream<Path> children = Files.list(root)) {
            orphans = children.filter(dir -> !jobs.containsKey(dir.getFileName().toString())).toList();
        } catch (IOException e) {
            log.warn("一時取得のフォルダーを走査できませんでした: directory={}", root, e);
            return;
        }
        for (Path dir : orphans) {
            try {
                if (Files.getLastModifiedTime(dir).toInstant().isBefore(deadline)) {
                    // yt-dlp に限る。パスを含むだけで選ぶと、そのフォルダーを見ているシェルまで止める（確認中に実際に起きた）。
                    // 結合中の ffmpeg は yt-dlp の子孫として一緒に止まる
                    String path = dir.toAbsolutePath().toString();
                    ProcessHandle.allProcesses()
                            .filter(h -> h.info().commandLine()
                                    .map(c -> c.contains("yt-dlp") && c.contains(path)).orElse(false))
                            .forEach(this::terminate);
                    delete(dir);
                }
            } catch (IOException e) {
                log.warn("一時取得のフォルダーの日時を読めませんでした: directory={}", dir, e);
            }
        }
    }

    /**
     * 利用者ごとに 1 件の枠を取る。確認と登録の間に同じ利用者の要求が割り込まないよう同期する。
     *
     * @param userId 利用者の ID
     * @return 登録した仕事（まだプロセスは無い）
     * @throws DeviceDownloadInProgressException 既に取得中の仕事がある場合
     */
    private synchronized Job reserve(Long userId) {
        boolean busy = jobs.values().stream()
                .anyMatch(job -> job.userId.equals(userId) && job.status == Status.RUNNING);
        if (busy) {
            throw new DeviceDownloadInProgressException();
        }
        Job job = new Job(UUID.randomUUID().toString(), userId, Instant.now());
        jobs.put(job.id, job);
        return job;
    }

    /**
     * 一時フォルダーを作って {@code yt-dlp} を起動し、終了を仮想スレッドで待つ。
     *
     * @param job 仕事
     * @param url 取得する URL
     * @throws IllegalStateException フォルダーを作れない、または {@code yt-dlp} を起動できない場合
     */
    private void launch(Job job, String url) {
        try {
            Files.createDirectories(job.directory());
        } catch (IOException e) {
            throw new IllegalStateException("一時フォルダーを作成できませんでした: " + job.directory());
        }
        List<String> command = VideoDownloadService.buildCommand(url, job.videoId, job.directory(),
                jsRuntime, monitorProperties.recording().maxHeight());
        try {
            job.process = processLauncher.launch(command, YtDlpLogFile.of(job.videoId));
        } catch (IOException e) {
            delete(job.directory());
            throw new IllegalStateException(
                    "yt-dlp を起動できませんでした（インストールされていないか、出力先のログファイルを作れない可能性があります）");
        }
        log.info("端末に保存する動画の取得を開始しました: job={}, video={}, user={}", job.id, job.videoId, job.userId);
        Thread.ofVirtual().name("device-download-" + job.id).start(() -> awaitCompletion(job));
    }

    /**
     * 終了を待って成否を決める。成否は終了コードではなく、再生できるファイルを用意できたかで決める
     * （{@code docs/pitfalls.md}「録画の成否は終了コードではなく…」。判断は録画と同じ {@link RecordingSalvager}）。
     *
     * <p>詰め替えて再生できる形にしたもの（{@code SALVAGED}）は {@link Status#PARTIAL} にする。{@code READY} にすると、
     * 音声の無い・途中で切れた動画を完成品として渡してしまう。{@code FAILED} にしないのは、yt-dlp の結合だけが失敗して
     * 中身は揃っている場合もあり、捨てると取り直すしかなくなるため（サービスへの保存の {@code PARTIAL} と同じ扱い）。
     *
     * @param job 仕事
     */
    private void awaitCompletion(Job job) {
        Integer exitCode = null;
        try {
            exitCode = job.process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        SalvageOutcome salvage = recordingSalvager.ensurePlayable(job.outputFile());
        Status status;
        if (!salvage.isPlayable()) {
            status = Status.FAILED;
        } else if (salvage.status() == SalvageStatus.SALVAGED) {
            status = Status.PARTIAL;
        } else {
            status = Status.READY;
        }
        job.completedAt = Instant.now();
        job.status = status;
        log.info("端末に保存する動画の取得が終わりました: job={}, video={}, status={}, exitCode={}",
                job.id, job.videoId, job.status, exitCode);
    }

    /**
     * ログイン中の利用者の仕事を返す。他人の仕事は無いものとして扱う。
     *
     * @param jobId 仕事 ID
     * @return 仕事
     * @throws DeviceDownloadNotFoundException 無い、または他人の仕事の場合
     */
    private Job ownedJob(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null || !job.userId.equals(currentAppUser.require().getId())) {
            throw new DeviceDownloadNotFoundException();
        }
        return job;
    }

    /**
     * 利用者の操作として監査ログに残す（{@link VideoDownloadService} と同じ {@code DOWNLOAD_REQUEST}）。
     *
     * @param user       操作した利用者
     * @param targetType 対象の種類（一時取得なら {@code DEVICE_DOWNLOAD}、既にある録画なら {@code RECORDING}）
     * @param targetId   対象の ID
     * @param url        利用者が入力した URL（認証情報を含まないのでそのまま残す）
     * @param videoId    動画 ID
     */
    private void audit(AppUser user, String targetType, String targetId, String url, String videoId) {
        auditLogger.record(AuditAction.DOWNLOAD_REQUEST, AuditOutcome.SUCCESS, user.getId(), user.getUsername(),
                null, targetType, targetId, "destination=device, url=" + url + ", video=" + videoId);
    }

    /**
     * プロセスとその子孫（結合中の ffmpeg）を止める。
     *
     * @param handle 止めるプロセス
     */
    private void terminate(ProcessHandle handle) {
        log.info("期限を過ぎた一時取得のプロセスを止めます: pid={}", handle.pid());
        if (ProcessTermination.terminateTreeAndAwait(handle, TERMINATION_GRACE)) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一時フォルダーを中身ごと消す。
     *
     * @param dir 消すフォルダー
     */
    private void delete(Path dir) {
        try {
            FileSystemUtils.deleteRecursively(dir);
            log.info("一時取得のフォルダーを消しました: directory={}", dir);
        } catch (IOException e) {
            log.warn("一時取得のフォルダーを消せませんでした: directory={}", dir, e);
        }
    }

    /**
     * 保存するときのファイル名。画面の {@code recordingDownloadName}（{@code common.js}）と同じ決まりにして、
     * どちらの経路で受け取っても同じ名前になるようにする。
     *
     * @param title   動画のタイトル
     * @param videoId タイトルが空のときに使う動画 ID
     * @return 「タイトル（使えない文字は {@code _}、100 文字まで）.mp4」
     */
    static String downloadName(String title, String videoId) {
        String safe = (title == null ? "" : title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        // 符号位置で数える。UTF-16 の単位で切ると絵文字が半分に割れるため（common.js と同じ）
        int[] codePoints = safe.codePoints().limit(100).toArray();
        String name = new String(codePoints, 0, codePoints.length);
        return (name.isEmpty() ? videoId : name) + ".mp4";
    }

    /**
     * 受け取れるファイル。
     *
     * @param path         ファイルの場所
     * @param downloadName 保存するときのファイル名
     */
    public record ReadyFile(Path path, String downloadName) {
    }

    /** 一時取得の仕事 1 件。状態は取得を待つ仮想スレッドが書き換えるので volatile にしている。 */
    private final class Job {
        private final String id;
        private final Long userId;
        private final Instant createdAt;
        private volatile String videoId;
        private volatile String title;
        private volatile Process process;
        private volatile Status status = Status.RUNNING;
        private volatile Instant completedAt;

        private Job(String id, Long userId, Instant createdAt) {
            this.id = id;
            this.userId = userId;
            this.createdAt = createdAt;
        }

        /**
         * 絶対パスにしているのは、再起動後に {@link #purgeExpired()} がこのパスを含む yt-dlp を
         * コマンドラインから探すため（相対パスで起動すると、絶対パスでは見つからない）。
         */
        private Path directory() {
            return Path.of(DeviceDownloadService.this.directory, id).toAbsolutePath();
        }

        private Path outputFile() {
            return directory().resolve(videoId + ".mp4");
        }

        private DeviceDownloadResponse toResponse() {
            String fileUrl = status.hasFile() ? "/api/my/downloads/device/" + id + "/file" : null;
            return new DeviceDownloadResponse(id, status, title, null, fileUrl);
        }
    }
}
