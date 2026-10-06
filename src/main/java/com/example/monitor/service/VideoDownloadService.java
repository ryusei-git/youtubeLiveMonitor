package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.DownloadResponse;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.exception.InsufficientDiskSpaceException;
import com.example.monitor.exception.LiveStreamDownloadRejectedException;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.exception.ServiceDownloadInProgressException;
import com.example.monitor.exception.VideoAlreadyDownloadedException;
import com.example.monitor.notification.DiscordNotifier;
import com.example.monitor.platform.StreamPlatform;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import com.example.monitor.util.ChannelLogContext;
import com.example.monitor.util.DiskSpaceUtils;
import com.example.monitor.util.ProcessTermination;
import com.example.monitor.util.RequestContext;
import com.example.monitor.util.YtDlpExitWatch;
import com.example.monitor.util.YtDlpExitWatch.ExitResult;
import com.example.monitor.util.YtDlpFormatSelector;
import com.example.monitor.util.YtDlpCookies;
import com.example.monitor.util.YtDlpJsRuntime;
import com.example.monitor.util.YtDlpLogFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * URL を指定して動画 1 本をダウンロードする。
 *
 * <h2>{@link StreamRecorder} と分けている理由</h2>
 * どちらも {@code yt-dlp} を起動するが、対象の性質が違う。
 * <ul>
 *   <li>{@code StreamRecorder} … <b>これから終わる</b>ライブ配信が相手。
 *       {@code --live-from-start} を付け、配信が終わるまで走り続ける。
 *       対象は必ず監視登録済みのチャンネルで、検知の結果として自動的に始まる。</li>
 *   <li>このクラス … <b>既に完結した</b>動画が相手。利用者が URL を貼った時点で対象が決まり、
 *       監視登録していないチャンネルの動画も対象になりうる。</li>
 * </ul>
 * 無理に 1 つにまとめると、どちらの都合なのか分からない分岐が増える。
 *
 * <h2>プラットフォームごとの違いを持ち込まない</h2>
 * URL の担当判定（{@link StreamPlatform#supportsUrl}）と、メタデータからチャンネル識別子を
 * 求める処理（{@link StreamPlatform#resolveChannelId}）は各プラットフォームの実装に任せる。
 * このクラスに {@code url.contains("youtube")} のような分岐を書き始めると、
 * プラットフォームが増えるたびにここが膨らむ（チャンネル登録が
 * {@code StreamPlatform.normalizeChannelInput()} に解決を任せているのと同じ考え方）。
 *
 * <h2>応答を待たせない</h2>
 * ダウンロードは動画の長さによっては数十分かかるため、プロセスを起動したらすぐに返し、
 * 完了の記録は仮想スレッドに任せる（録画と同じ作り）。進捗は録画履歴の状態
 * （{@code RECORDING} → {@code COMPLETED} / {@code PARTIAL} / {@code FAILED}）で分かる。
 *
 * <p>{@code yt-dlp} の出力は JVM へのパイプではなく {@link YtDlpLogFile} のファイルへ書かせている。
 * パイプだと、アプリを再起動したときに読み手がいなくなり、{@code yt-dlp} が次の出力で止まるため
 * （{@link StreamRecorder} のクラス JavaDoc 参照）。ファイルなら再起動しても最後までダウンロードする。
 * 完了を待つ仮想スレッドは失われるが、{@link RecordingReconciler} が {@code RECORDING} のまま残った履歴を
 * 実ファイルの有無で補正するため、録画と同じように救済される。
 *
 * <p>完了は録画と同じ {@link YtDlpExitWatch} で待ち、出力が止まった yt-dlp と空き容量が下限を割ったときの yt-dlp は
 * 子孫ごと止める。以前はこのクラスだけ {@link Process#waitFor()} で上限なく待っており、固まると {@code RECORDING} のまま、
 * 動画 ID も予約されたまま残った（削除は 409、後始末も「処理中」として触らない）。
 * 見回りを録画と共有しているのは、片方だけ直すずれを防ぐため。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoDownloadService {

    /**
     * チャンネル識別子を特定できなかった動画の置き場所（録画ディレクトリ配下）。
     *
     * <p>Twitch の VOD のように、{@code yt-dlp} からも API からもチャンネルを特定できない
     * ことがある。保存先はチャンネル識別子ごとのディレクトリという決まりなので、
     * 特定できないものだけをここへまとめる。
     *
     * <p><b>チャンネル識別子と衝突しない名前にしてある。</b>YouTube のチャンネル ID は
     * {@code UC} で始まり、Twitch のユーザー ID は数字だけなので、この名前になることはない。
     * 衝突すると、実在するチャンネルの録画がこちらに混ざる。
     */
    static final String UNLINKED_DIRECTORY = "downloads";

    private final MonitorProperties monitorProperties;
    private final StreamPlatformRegistry streamPlatformRegistry;
    private final VideoSourceProbe videoSourceProbe;
    private final ProcessLauncher processLauncher;
    private final RecordingHistoryService recordingHistoryService;
    private final RecordingSalvager recordingSalvager;
    private final RecordingFileService recordingFileService;
    private final RecordingRepository recordingRepository;
    private final MonitoredChannelRepository monitoredChannelRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogger auditLogger;

    /**
     * {@code yt-dlp} に {@code --js-runtimes} で渡す JavaScript のランタイム（空なら付けない）。
     * 理由は {@link YtDlpJsRuntime} を参照。
     *
     * <p>{@link MonitorProperties.RecordingProperties} に入れていないのは、record にフィールドを足すと
     * 正準コンストラクタを直接呼んでいるテストがコンパイルエラーになるため（{@link StreamRecorder} と同じ）。
     * final でないので {@code @RequiredArgsConstructor} のコンストラクタも変わらない。
     */
    @Value("${monitor.recording.js-runtime:}")
    private String jsRuntime = "";

    /**
     * {@code yt-dlp} に {@code --cookies} で渡す Cookie ファイル（無ければ付けない）。
     * 理由は {@link YtDlpCookies} を参照。{@link #jsRuntime} と同じく、Spring を通さずに組み立てるテストでは空のまま。
     */
    @Value("${monitor.recording.cookies-file:}")
    private String cookiesFile = "";

    /**
     * Twitch の最大の高さ（ピクセル。0 以下で上限なし）。自動録画（{@link StreamRecorder}）と同じ設定を使う。
     * 理由は {@link YtDlpFormatSelector} を参照。
     */
    @Value("${monitor.recording.twitch-max-height:720}")
    private int twitchMaxHeight = 720;

    /**
     * ダウンロードを始めるのに必要な空き容量（GB）。自動録画（{@link StreamRecorder}）と同じ設定を使う。
     * 録画と手動ダウンロードは同じボリュームに書くため、片方だけ確かめても満杯は防げない。
     * ダウンロード中も、録画と同じく {@link DiskSpaceUtils#reserveBytes(long)} を下限に見回り、割ったら止める（{@link YtDlpExitWatch}）。
     *
     * <p>初期値を 0（確認しない）にしているのは、Spring を通さずに組み立てるテストが、
     * 実行した機械の空き容量に左右されないようにするため（{@link StreamRecorder} と同じ）。
     */
    @Value("${monitor.recording.min-free-gb:20}")
    private long minFreeGb = 0;

    /**
     * 出力がこの分数だけ更新されなければ、固まったとみなしてダウンロードを止める（{@link YtDlpExitWatch}）。
     * 自動録画（{@link StreamRecorder}）と同じ設定キーを読む。同じ yt-dlp・同じ出力の形なので、しきい値を分ける理由が無い。
     * 30 分と長めに取る理由は {@link StreamRecorder} の同名のフィールドを参照。
     *
     * <p>{@link MonitorProperties.RecordingProperties} に入れない理由は {@link #jsRuntime} と同じ。
     */
    @Value("${monitor.recording.stall-minutes:30}")
    private long stallMinutes = 30;

    /**
     * ダウンロード中の動画 ID の予約。同じ動画を二重にダウンロードしないために使う。
     *
     * <p>DB の履歴（{@link RecordingRepository#existsByVideoId}）だけでは足りない。
     * 履歴の確認と登録の間に別のリクエストが入り込むと、<b>どちらも「まだ無い」と判断して
     * 同じ出力先へ 2 つの {@code yt-dlp} を起動</b>し、互いの出力を壊す。
     *
     * <p><b>自動録画（{@link StreamRecorder}）と予約を共有している。</b>手動ダウンロード中の
     * 配信の自動録画が次の巡回で始まる、という経路があるため、それぞれが別々の集合を持つと
     * 同時開始の競合を防げない（詳細は {@link ActiveVideoJobs} の JavaDoc 参照）。
     *
     * <p>失敗の履歴を消して取り直すとき（{@code discardFailedHistory}）も、予約を持ったまま消す。
     * 予約が無いと、同じ動画の自動録画や後始末が触っている最中のファイルまで消しうる。
     */
    private final ActiveVideoJobs activeVideoJobs;

    /** 下調べ中でまだ動画 ID が分からない枠の値。ConcurrentHashMap は null を値に持てないため空文字にしている。 */
    private static final String PROBING = "";

    /**
     * 一般利用者（ADMIN 以外）の「サービスに保存」の枠。利用者 ID → 保存中の動画 ID（下調べ中は {@link #PROBING}）。
     *
     * <p>開始時点の空き容量の判定だけでは、長い動画を同時に何本も始めると、どれも判定を通ったうえで
     * 終わる頃にしきい値を割り、自動録画（{@link StreamRecorder}）が始まらなくなる。
     * 利用者 1 人の操作で監視対象の配信が録れなくなるのを防ぐため、一般利用者は 1 人同時 1 件にしている
     * （端末に保存の {@link DeviceDownloadService} と同じ考え方。枠はそちらとは別に数える）。
     *
     * <p><b>管理者は対象外。</b>運用する本人で、{@code /api/downloads} も ADMIN 専用のため。
     * 上限は呼ばれた口ではなく役割で決める。なので、管理者が利用者の画面（{@code /api/my/downloads}）から
     * 保存しても上限はかからない。
     *
     * <p><b>メモリだけに持つ</b>（{@link DeviceDownloadService} と同じ）。再起動直後は、まだ動いている
     * {@code yt-dlp} の分を数えないので、2 件目を受け付けうる。逆に、完了待ちのスレッドが再起動で失われても、
     * 枠が外れないまま残る事故は起きない（{@code docs/pitfalls.md}「録画中にアプリを再起動すると
     * 「録画中」のまま更新されなくなる」）。
     *
     * <p>枠は完了待ち（{@link #awaitCompletion}）が終わるまで外れない。{@code yt-dlp} が固まっても、完了待ちが
     * 出力の止まった {@code yt-dlp} を {@link #stallMinutes} 分で止める（{@link YtDlpExitWatch}）ので、再起動を待たずに外れる
     * （端末に保存の枠は、期限切れで止めるまで外れない）。
     */
    private final Map<Long, String> userSlots = new ConcurrentHashMap<>();

    /**
     * 固まったダウンロードを止めたことを管理者へ知らせるために使う（{@link YtDlpExitWatch}。録画と同じ）。
     *
     * <p>ダウンロードの失敗（{@code FAILED}）そのものは知らせない。操作した人が画面で結果を見るため。
     * 止めたときだけ知らせるのは、yt-dlp の更新や空き容量の確保が要る兆しで、操作した人には分からないため。
     */
    private final DiscordNotifier discordNotifier;

    /**
     * URL を指定して動画のダウンロードを開始する。
     *
     * <p>プロセスを起動したらすぐに返る（完了は待たない）。
     *
     * <p>ライブ配信・待機所の URL（{@code live_status} が {@code is_live} / {@code is_upcoming}）は
     * {@code yt-dlp} のダウンロードプロセスを起動する前に拒否する。理由は
     * {@link LiveStreamDownloadRejectedException} の JavaDoc を参照。
     *
     * <p>失敗（{@code FAILED}）の履歴だけが残っている動画は、その履歴とファイルを消して取り直す
     * （{@code discardFailedHistory} の JavaDoc 参照）。
     *
     * @param rawUrl 利用者が入力した動画の URL
     * @return 受け付けた内容（録画履歴の主キー・動画 ID・タイトル・紐づいたチャンネル）
     * @throws IllegalArgumentException           URL が空、対応していないプラットフォーム、
     *                                            または動画の情報を取得できなかった場合
     * @throws LiveStreamDownloadRejectedException 配信中・配信開始前の URL の場合
     * @throws VideoAlreadyDownloadedException    同じ動画を取得中、または再生できる録画（完了・途中まで）が
     *                                            既にある場合。失敗の履歴だけなら投げずに、消して取り直す
     * @throws InsufficientDiskSpaceException     空き容量がしきい値を下回る場合、または空きが足りずに詰め替えを
     *                                            見送った失敗の録画が残っている場合（503。後者は何も消さない）
     * @throws ServiceDownloadInProgressException 一般利用者が既に 1 件保存中の場合（409）
     * @throws IllegalStateException              保存先を作れない、
     *                                            {@code yt-dlp} を起動できない場合
     */
    public DownloadResponse startDownload(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("動画のURLを入力してください");
        }
        // 利用者にも開いた口なので、満杯にして録画と H2 まで巻き込まないよう最初に断る。
        // 容量を読めなかったときは始める（「判定できなかった」を「満杯」と扱わない。StreamRecorder と同じ）
        if (DiskSpaceUtils.isBelow(Path.of(monitorProperties.recording().directory()), minFreeGb)) {
            throw new InsufficientDiskSpaceException();
        }
        String url = rawUrl.trim();

        // 対応していない URL は、プロセスを起動する前にその理由で返す
        StreamPlatform platform = streamPlatformRegistry.findByUrl(url);

        // 一般利用者は同時に 1 件まで。下調べ（数秒かかる）の前に枠を取る。後にすると、連打した 2 件がどちらも通る
        Long limitedUserId = limitedUserId();
        if (limitedUserId != null && userSlots.putIfAbsent(limitedUserId, PROBING) != null) {
            throw new ServiceDownloadInProgressException();
        }
        boolean started = false;
        try {
            VideoSource source = videoSourceProbe.probe(url)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "動画の情報を取得できませんでした。URLを確認してください: " + url));

            // ライブ配信・待機所は自動録画の担当。プロセスを起動する前、予約する前に打ち切る
            if (source.isLiveOrUpcoming()) {
                throw new LiveStreamDownloadRejectedException(source.videoId());
            }

            String videoId = source.videoId();
            if (!activeVideoJobs.reserve(videoId)) {
                throw new VideoAlreadyDownloadedException(videoId);
            }

            try {
                if (recordingRepository.existsByVideoId(videoId)) {
                    discardFailedHistory(videoId);
                }

                if (limitedUserId != null) {
                    // 起動より前に書く。起動後だと、すぐ終わったダウンロードの awaitCompletion が先に枠を外そうとして
                    // 空振りし、この利用者の枠が再起動まで残る
                    userSlots.put(limitedUserId, videoId);
                }
                DownloadResponse accepted = launch(platform, source, url);
                started = true;
                recordDownloadRequest(accepted, url);
                return accepted;
            } finally {
                if (!started) {
                    // 起動できなかった登録を残すと、この動画は以降永久にダウンロードできなくなる
                    activeVideoJobs.release(videoId);
                }
            }
        } finally {
            if (!started && limitedUserId != null) {
                // 起動できなかった枠を残すと、この利用者は再起動まで保存できなくなる
                userSlots.remove(limitedUserId);
            }
        }
    }

    /**
     * 失敗（{@code FAILED}）の履歴だけが残っている動画なら、その履歴とファイルを消して取り直せるようにする。
     *
     * <p><b>なぜ消して取り直すのか。</b>以前は状態を問わず断り、録画履歴を先に消すよう求めていた。
     * #450 でこの処理を一般利用者にも開いたが、録画を消す API（{@code /api/recordings/**}）は管理者だけなので、
     * 一時的な通信エラーで失敗した動画は、利用者からは二度と保存できなかった。{@code FAILED} は再生できるものが
     * 無い状態（{@link RecordingStatus#FAILED}）で、利用者のアーカイブにも出ない。
     * 「既にサービスの録画にあるときは取り直さない」（#449 の決定）は、再生できる録画（完了・途中まで）があるときに守る。
     * ファイルも消すのは、再生できない {@code {動画ID}.mp4} や断片が残っていると、yt-dlp が出力先にある
     * ファイルを使い回して、また失敗になりうるため。
     *
     * <p><b>空きが足りずに詰め替えを見送った録画が 1 件でもあれば、何も消さずに 503 で断る。</b>
     * この録画は {@code FAILED} でも元のファイル・断片が残っていて、空きができれば後始末
     * （{@link RecordingReconciler}）が途中まで・完了に直す（{@link SalvageStatus#INSUFFICIENT_SPACE}）。
     * 開始時の空き容量の確認（{@link #minFreeGb}）は録画の大きさを見ないので、大きな録画では、その確認を通っても
     * 詰め替えに要る「録画の大きさ＋下限」に足りないことがある。ここで消すと、直せたはずの録画を利用者の操作で失う。
     * 判定は後始末の見送りと同じもの（{@link RecordingSalvager#lacksSpaceToSalvage}）を使う。
     * 空きができてから、次の後始末がこの録画を直すまでの間に押されると、判定は通って消す。
     *
     * <p><b>1 件でも失敗以外の履歴があれば何も消さない。</b>{@link RecordingHistoryService#deleteRecording(Long)} は
     * 同じフォルダーにある同じ動画 ID のファイルをまとめて消すので、完了した録画と同じ動画 ID の失敗の履歴を消すと、
     * 完了した録画のファイルまで消える。
     *
     * <p><b>呼び出し元がこの動画 ID を {@link ActiveVideoJobs} で予約していること。</b>予約を持ったまま消すので、
     * 同じ動画の自動録画や後始末（{@link RecordingReconciler}）が触っている最中のファイルを消すことは無い
     * （後始末も予約を取ってから行を読み直す）。{@link RecordingHistoryService#deleteRecording(Long)} は予約を見ないので、
     * 予約を持ったまま呼べる。予約を見るように変えるなら、ここも合わせて直す。
     *
     * <p><b>OS 上のプロセスは確かめない</b>（後始末の 2 段階の確認のうち、
     * {@link ProcessLauncher#isRunningWithCommandLineContaining(String)} は使わない）。「端末に保存」
     * （{@link DeviceDownloadService}）の {@code yt-dlp} も同じ動画 ID をコマンドラインに含むので、確かめると、
     * 誰かが端末に保存しているだけで断ってしまうため。予約で避けられないのは、再起動前の JVM が失敗の行に始めた
     * 詰め替えの {@code ffmpeg} が生き残っている間（1 件最大 600 秒）だけ（{@code docs/pitfalls.md}
     * 「録画中にアプリを再起動すると「録画中」のまま更新されなくなる」）。そのファイルを消しても再生できるものは
     * 失わないが、後始末が直せたはずの録画は失いうる（{@link RecordingStatus#FAILED}）。消せずに残って
     * 取り直しがまた失敗しても、その {@code ffmpeg} が終わった後にもう一度保存すれば通る。
     *
     * <p>監査ログには、操作した人の操作として {@code RECORDING_DELETE}（消した失敗の履歴）と
     * {@code DOWNLOAD_REQUEST}（取り直し）が並ぶので、何を消して取り直したかを後から追える。
     *
     * @param videoId 動画 ID
     * @throws VideoAlreadyDownloadedException 再生できる録画がある場合（保存済みの文言）。取得中の履歴がある、
     *                                         または確かめている間に履歴が消えた場合（取得中の文言。もう一度押せば通る）
     * @throws InsufficientDiskSpaceException  空きが足りずに詰め替えを見送った失敗の録画がある場合（何も消さない）
     */
    private void discardFailedHistory(String videoId) {
        List<Recording> history = recordingRepository.findByVideoId(videoId);
        boolean playable = history.stream().anyMatch(recording -> recording.getStatus() == RecordingStatus.COMPLETED
                || recording.getStatus() == RecordingStatus.PARTIAL);
        if (playable) {
            throw VideoAlreadyDownloadedException.alreadySaved(videoId);
        }
        // RECORDING が残っている（再起動で追跡を失った取得がまだ動いている・後始末を待っている）か、
        // existsByVideoId の後に行が消えた（管理者が同時に消した）。どちらも少し待てば通る
        boolean onlyFailed = !history.isEmpty()
                && history.stream().allMatch(recording -> recording.getStatus() == RecordingStatus.FAILED);
        if (!onlyFailed) {
            throw new VideoAlreadyDownloadedException(videoId);
        }
        // 空きが足りずに詰め替えを見送った録画は、中身が残っていて後始末が直せる。消すと直せたはずの録画を
        // 失うので、1 件でもあれば何も消さずに断る（必要量と空きは RecordingSalvager が WARN に残す）
        for (Recording failed : history) {
            if (recordingSalvager.lacksSpaceToSalvage(recordingFileService.resolveFilePath(failed))) {
                log.warn("空きが足りず詰め替えを見送った録画が残っているため、失敗の履歴を消さずに断ります: video={}, id={}",
                        videoId, failed.getId());
                throw new InsufficientDiskSpaceException();
            }
        }
        for (Recording failed : history) {
            try {
                recordingHistoryService.deleteRecording(failed.getId());
            } catch (RecordingNotFoundException e) {
                // 確かめてから消すまでの間に、管理者が同じ履歴を消した。消えていれば目的は果たしている
            }
        }
        log.info("失敗で終わった履歴を消して、取り直します: video={}, count={}", videoId, history.size());
    }

    /**
     * 「サービスに保存」の同時 1 件の上限をかける利用者を求める。
     *
     * <p>上限は呼ばれた口ではなく役割で決める（理由は {@link #userSlots} を参照）。
     * 利用者の引き方は {@link #recordDownloadRequest} と同じ。
     *
     * @return 同時 1 件の上限をかける利用者の ID。未ログイン・管理者・DB に無い利用者なら {@code null}
     */
    private Long limitedUserId() {
        String username = RequestContext.currentUsername();
        if (username == null) {
            return null;
        }
        return appUserRepository.findByUsername(username)
                .filter(user -> user.getRole() != AppUser.Role.ADMIN)
                .map(AppUser::getId)
                .orElse(null);
    }

    /**
     * この動画を現在ダウンロード中かどうかを返す。
     *
     * <p>{@link RecordingReconciler} が「まだ処理中のものを失敗と誤判定しない」ために使う
     * （{@link StreamRecorder#isRecording(String)} と同じ役割）。
     *
     * @param videoId 確認したい動画 ID
     * @return ダウンロード中であれば {@code true}
     */
    public boolean isDownloading(String videoId) {
        return activeVideoJobs.isActive(videoId);
    }

    /**
     * URL 指定ダウンロードの要求を監査ログへ記録する。
     *
     * <p>{@code MonitoredChannelService.recordChannelAction()} と同じ考え方で操作者を解決する。
     * {@code rawUrl} は利用者が入力した動画の URL そのものであり、認証情報を含まないため
     * そのまま {@code detail} に残してよい。
     *
     * @param accepted 受け付けたダウンロードの内容
     * @param rawUrl   利用者が入力した動画の URL
     */
    private void recordDownloadRequest(DownloadResponse accepted, String rawUrl) {
        String username = RequestContext.currentUsername();
        Long userId = username == null ? null
                : appUserRepository.findByUsername(username).map(AppUser::getId).orElse(null);
        auditLogger.record(AuditAction.DOWNLOAD_REQUEST, AuditOutcome.SUCCESS, userId, username, null,
                "RECORDING", String.valueOf(accepted.recordingId()),
                "url=" + rawUrl + ", video=" + accepted.videoId());
    }

    /**
     * 保存先を用意して {@code yt-dlp} を起動し、録画履歴に開始を記録する。
     *
     * <p>{@link RecordingHistoryService#recordStart} が失敗した場合は、起動済みのプロセスを
     * {@link ProcessTermination#destroyForciblyAndAwait(Process)} で停止・終了確認してから
     * 例外を投げ直す。ここで止めずに終わると、次の巡回・再試行で同じ出力先に別プロセスが
     * 起動しうる（{@link StreamRecorder#startRecording} と同じ理由）。
     *
     * @param platform この URL を担当するプラットフォーム
     * @param source   {@code yt-dlp} が返した動画のメタデータ
     * @param url      ダウンロード対象の URL
     * @return 受け付けた内容
     * @throws IllegalStateException 保存先を作れない、または {@code yt-dlp} を起動できない場合
     * @throws RuntimeException      {@link RecordingHistoryService#recordStart} が失敗した場合。
     *                               起動済みのプロセスは停止済みの状態で伝播する
     */
    private DownloadResponse launch(StreamPlatform platform, VideoSource source, String url) {
        String videoId = source.videoId();
        String channelId = platform.resolveChannelId(source).orElse(null);
        // 登録されていないチャンネルなら紐づけない（紐づけたいがために勝手に監視対象へ登録はしない）
        MonitoredChannel channel = channelId == null ? null
                : monitoredChannelRepository.findByYoutubeChannelId(channelId).orElse(null);

        String directoryName = channelId == null ? UNLINKED_DIRECTORY : channelId;
        Path outputDirectory = Path.of(monitorProperties.recording().directory(), directoryName);
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("保存先ディレクトリを作成できませんでした: " + outputDirectory);
        }

        Path outputFile = outputDirectory.resolve(videoId + ".mp4");
        String channelName = channel == null ? "(未登録)" : channel.getChannelName();
        // 区切り文字は OS に依らず "/"（RecordingFileService.toRelativePath と揃える）
        String relativeFilePath = directoryName + "/" + videoId + ".mp4";

        Process process;
        try {
            process = processLauncher.launch(buildCommand(url, videoId, outputDirectory, jsRuntime, cookiesFile,
                    YtDlpFormatSelector.of(platform.platform(), monitorProperties.recording().maxHeight(),
                            twitchMaxHeight)), YtDlpLogFile.of(videoId));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "yt-dlp を起動できませんでした（インストールされていないか、出力先のログファイルを作れない可能性があります）");
        }

        // タイトルが取れなかった場合も一覧で見分けが付くよう、動画IDで代用する
        String title = source.title() == null ? videoId : source.title();
        Recording recording;
        try {
            recording = recordingHistoryService.recordStart(channel, videoId, title, relativeFilePath);
        } catch (RuntimeException e) {
            log.error("録画履歴の登録に失敗したため、起動済みのダウンロードプロセスを停止します: video={}",
                    videoId, e);
            if (ProcessTermination.destroyForciblyAndAwait(process)) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }

        log.info("動画のダウンロードを開始しました: video={}, title={}, channel={}, directory={}",
                videoId, title, channelName, outputDirectory);

        Thread.ofVirtual()
                .name("download-" + videoId)
                .start(() -> awaitCompletion(process, recording.getId(), videoId, outputFile, channelId, channelName));

        return new DownloadResponse(
                recording.getId(),
                platform.platform(),
                videoId,
                title,
                channelId,
                channel == null ? null : channel.getChannelName(),
                relativeFilePath);
    }

    /**
     * {@code yt-dlp} に渡すコマンドを組み立てる。
     *
     * <p>ライブ録画（{@link StreamRecorder}）との違いは {@code --live-from-start} を<b>付けない</b>こと。
     * 対象は既に完結した動画なので指定しても意味が無い。
     * {@code --no-playlist} は、再生リスト付きの URL（{@code &list=...}）を貼られたときに
     * <b>一覧まるごとを取り込んでしまうのを防ぐ</b>ために要る（利用者は 1 本のつもりで貼っている）。
     *
     * <p>{@code --merge-output-format mp4} で最終ファイル名を録画開始前に確定させている理由は
     * {@link StreamRecorder} のクラス JavaDoc を参照。
     *
     * <p>{@code --no-progress} を付けるのは、進捗行が出力のほとんどを占め、ローテーションの無い
     * {@link YtDlpLogFile} のファイルが 1 本で数十 MB になるため（録画と同じ理由）。
     *
     * <p>「端末に保存」（{@link DeviceDownloadService}）も同じコマンドで取得するため、状態を持たない
     * static にしている。付けるオプションの理由はどちらも同じで、片方だけ直す事故を防ぐため。
     *
     * @param url             ダウンロード対象の URL
     * @param videoId         動画 ID（出力ファイル名に使う）
     * @param outputDirectory 保存先ディレクトリ
     * @param jsRuntime       {@code --js-runtimes} に渡すランタイム（空なら付けない）
     * @param cookiesFile     {@code --cookies} に写しを渡す Cookie ファイル（無ければ付けない。{@link YtDlpCookies}）。
     *                        写しを作るので、起動の直前に呼ぶ
     * @param formatSelector  {@code -f} に渡す指定（{@link YtDlpFormatSelector}。上限はプラットフォームで変わる）
     * @return {@code yt-dlp} 実行コマンド
     */
    static List<String> buildCommand(String url, String videoId, Path outputDirectory,
                                     String jsRuntime, String cookiesFile, String formatSelector) {
        String outputTemplate = outputDirectory.resolve(videoId + ".%(ext)s").toString();
        List<String> command = new ArrayList<>();
        command.add("yt-dlp");
        command.addAll(YtDlpJsRuntime.options(jsRuntime));
        command.addAll(YtDlpCookies.options(cookiesFile));
        command.addAll(List.of(
                "--no-part",
                "--no-progress",
                "--no-playlist",
                "--merge-output-format", "mp4",
                "-f", formatSelector,
                "-o", outputTemplate,
                url));
        return command;
    }

    /**
     * ダウンロードプロセスの終了を待って結果を記録する。
     *
     * @param process     起動済みのダウンロードプロセス
     * @param recordingId {@link RecordingHistoryService#recordStart}で発行された録画履歴の主キー
     * @param videoId     対象の動画 ID
     * @param outputFile  完成予定のファイルのパス
     * @param channelId   紐づいたチャンネル識別子。無ければ {@code null}
     * @param channelName ログと通知に出すチャンネル名（紐づくチャンネルが無ければ {@code "(未登録)"}）
     */
    void awaitCompletion(Process process, Long recordingId, String videoId, Path outputFile, String channelId,
                         String channelName) {
        try {
            // チャンネルが分かっているならチャンネル別ログにも残す（後から経緯を追えるように）
            if (channelId == null) {
                runToCompletion(process, recordingId, videoId, outputFile, channelName);
            } else {
                ChannelLogContext.runWithChannel(channelId,
                        () -> runToCompletion(process, recordingId, videoId, outputFile, channelName));
            }
        } catch (RuntimeException e) {
            // 仮想スレッドの既定の処理（標準エラー）に流さず、アプリのログ（/logs 画面の「システム」）に残す。
            // RECORDING のまま残った行は、予約を外した後に RecordingReconciler が補正する
            log.error("ダウンロードの結果を記録できませんでした。録画履歴は後始末（RecordingReconciler）が補正します: video={}",
                    videoId, e);
        } finally {
            // 利用者の枠も、結果を記録し終えてから外す
            userSlots.values().remove(videoId);
            // 結果を記録し終えてから追跡を外す。順序を逆にすると、その隙に RecordingReconciler が
            // 「処理中でないのに RECORDING のまま＝置き去り」と誤判定してしまう
            activeVideoJobs.release(videoId);
        }
    }

    /**
     * プロセスの終了を待ち、結果を録画履歴に反映する。
     *
     * <p>出力は {@link YtDlpLogFile} のファイルへ向けているため（クラスの JavaDoc 参照）、ここでは読まない。
     * 出力が止まった yt-dlp と、空き容量が下限を割ったときの yt-dlp は {@link YtDlpExitWatch#awaitExit} が子孫ごと止める
     * （録画と同じ）。止めた後は、止まるまでの分を救済して記録する（録り直しは無い）。
     *
     * @param process     起動済みのダウンロードプロセス
     * @param recordingId 録画履歴の主キー
     * @param videoId     対象の動画 ID
     * @param outputFile  完成予定のファイルのパス
     * @param channelName ログと通知に出すチャンネル名（紐づくチャンネルが無ければ {@code "(未登録)"}）
     */
    private void runToCompletion(Process process, Long recordingId, String videoId, Path outputFile,
                                 String channelName) {
        ExitResult exit = YtDlpExitWatch.awaitExit(process, "ダウンロード", channelName, videoId, outputFile.getParent(),
                stallMinutes, minFreeGb, discordNotifier::sendAdminAlert);
        if (exit.exitCode() != null) {
            // プロセスが終わった後に残る .temp.mp4 は結合の書きかけ。詰め替えの前に空きを返す（録画と同じ）
            YtDlpExitWatch.deleteMergeLeftover(outputFile.getParent(), videoId);
        }
        // チャンネルの削除で行が連鎖削除され、プロセスも止められたときは、詰め替えも記録もしない
        // （StreamRecorder.awaitCompletion と同じ）。詰め替えても、記録する行が無い
        if (!recordingHistoryService.exists(recordingId)) {
            log.info("録画履歴が削除されているため（チャンネルの削除）、ダウンロードの結果を記録しません: video={}, exitCode={}",
                    videoId, exit.exitCode());
            return;
        }
        // 待機が中断されたとき（exitCode が null）も、既にファイルが出来ていれば成功として扱う
        recordOutcome(recordingId, videoId, outputFile, exit.exitCode());
    }

    /**
     * ダウンロードの成否を確定させて履歴に記録する。
     *
     * <p><b>判断材料は再生できるファイルを用意できたかどうかだけで、終了コードは使わない。</b>
     * 理由は {@link StreamRecorder} のクラス JavaDoc を参照（終了コード 1 でも再生できる
     * ファイルが完成していることがある）。途中で切れたものは {@link RecordingSalvager} が
     * 再生できる形に直し、{@code PARTIAL} として残す。
     *
     * @param recordingId 録画履歴の主キー
     * @param videoId     対象の動画 ID
     * @param outputFile  完成予定のファイルのパス
     * @param exitCode    {@code yt-dlp} の終了コード。取得できなかった場合は {@code null}
     */
    private void recordOutcome(Long recordingId, String videoId, Path outputFile, Integer exitCode) {
        // プロセスは既に終了しているので、書き込み中のファイルを壊す心配なく詰め替えられる
        SalvageOutcome salvage = recordingSalvager.ensurePlayable(outputFile);

        if (!salvage.isPlayable()) {
            log.warn("再生できるファイルを用意できなかったため失敗として記録します: video={}, exitCode={}",
                    videoId, exitCode);
            recordingHistoryService.markFailed(recordingId);
            return;
        }

        if (salvage.status() == SalvageStatus.SALVAGED) {
            recordingHistoryService.markPartial(recordingId, salvage.fileSizeBytes());
            log.info("途中までしか取得できなかったため、そこまでを再生できる形にして記録します: "
                    + "video={}, size={}, exitCode={}", videoId, salvage.fileSizeBytes(), exitCode);
            return;
        }

        recordingHistoryService.markCompleted(recordingId, salvage.fileSizeBytes());
        log.info("動画のダウンロードが完了しました: video={}, size={}, exitCode={}",
                videoId, salvage.fileSizeBytes(), exitCode);
    }
}
