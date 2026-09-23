package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.DownloadResponse;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.exception.LiveStreamDownloadRejectedException;
import com.example.monitor.exception.VideoAlreadyDownloadedException;
import com.example.monitor.platform.StreamPlatform;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import com.example.monitor.util.ChannelLogContext;
import com.example.monitor.util.ProcessTermination;
import com.example.monitor.util.YtDlpFormatSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 * <p>アプリを再起動すると完了を待つ仮想スレッドは失われるが、
 * {@link RecordingReconciler} が {@code RECORDING} のまま残った履歴を
 * 実ファイルの有無で補正するため、録画と同じように救済される。
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
    private final RecordingRepository recordingRepository;
    private final MonitoredChannelRepository monitoredChannelRepository;

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
     */
    private final ActiveVideoJobs activeVideoJobs;

    /**
     * URL を指定して動画のダウンロードを開始する。
     *
     * <p>プロセスを起動したらすぐに返る（完了は待たない）。
     *
     * <p>ライブ配信・待機所の URL（{@code live_status} が {@code is_live} / {@code is_upcoming}）は
     * {@code yt-dlp} のダウンロードプロセスを起動する前に拒否する。理由は
     * {@link LiveStreamDownloadRejectedException} の JavaDoc を参照。
     *
     * @param rawUrl 利用者が入力した動画の URL
     * @return 受け付けた内容（録画履歴の主キー・動画 ID・タイトル・紐づいたチャンネル）
     * @throws IllegalArgumentException           URL が空、対応していないプラットフォーム、
     *                                            または動画の情報を取得できなかった場合
     * @throws LiveStreamDownloadRejectedException 配信中・配信開始前の URL の場合
     * @throws VideoAlreadyDownloadedException    同じ動画の録画履歴が既にある、
     *                                            または既に処理中の場合
     * @throws IllegalStateException              保存先を作れない、{@code yt-dlp} を起動できない場合
     */
    public DownloadResponse startDownload(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("動画のURLを入力してください");
        }
        String url = rawUrl.trim();

        // 対応していない URL は、プロセスを起動する前にその理由で返す
        StreamPlatform platform = streamPlatformRegistry.findByUrl(url);

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

        boolean started = false;
        try {
            if (recordingRepository.existsByVideoId(videoId)) {
                throw new VideoAlreadyDownloadedException(videoId);
            }

            DownloadResponse accepted = launch(platform, source, url);
            started = true;
            return accepted;
        } finally {
            if (!started) {
                // 起動できなかった登録を残すと、この動画は以降永久にダウンロードできなくなる
                activeVideoJobs.release(videoId);
            }
        }
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
        // 区切り文字は OS に依らず "/"（RecordingFileService.toRelativePath と揃える）
        String relativeFilePath = directoryName + "/" + videoId + ".mp4";

        Process process;
        try {
            process = processLauncher.launch(buildCommand(url, videoId, outputDirectory));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "yt-dlp を起動できませんでした（インストールされていない可能性があります）");
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
                videoId, title, channel == null ? "(未登録)" : channel.getChannelName(), outputDirectory);

        Thread.ofVirtual()
                .name("download-" + videoId)
                .start(() -> awaitCompletion(process, recording.getId(), videoId, outputFile, channelId));

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
     * @param url             ダウンロード対象の URL
     * @param videoId         動画 ID（出力ファイル名に使う）
     * @param outputDirectory 保存先ディレクトリ
     * @return {@code yt-dlp} 実行コマンド
     */
    private List<String> buildCommand(String url, String videoId, Path outputDirectory) {
        String outputTemplate = outputDirectory.resolve(videoId + ".%(ext)s").toString();
        return List.of(
                "yt-dlp",
                "--no-part",
                "--no-playlist",
                "--merge-output-format", "mp4",
                "-f", YtDlpFormatSelector.of(monitorProperties.recording().maxHeight()),
                "-o", outputTemplate,
                url);
    }

    /**
     * ダウンロードプロセスの出力を読み切り、終了を待って結果を記録する。
     *
     * @param process     起動済みのダウンロードプロセス
     * @param recordingId {@link RecordingHistoryService#recordStart}で発行された録画履歴の主キー
     * @param videoId     対象の動画 ID
     * @param outputFile  完成予定のファイルのパス
     * @param channelId   紐づいたチャンネル識別子。無ければ {@code null}
     */
    void awaitCompletion(Process process, Long recordingId, String videoId, Path outputFile, String channelId) {
        try {
            // チャンネルが分かっているならチャンネル別ログにも残す（後から経緯を追えるように）
            if (channelId == null) {
                runToCompletion(process, recordingId, videoId, outputFile);
            } else {
                ChannelLogContext.runWithChannel(channelId,
                        () -> runToCompletion(process, recordingId, videoId, outputFile));
            }
        } finally {
            // 結果を記録し終えてから追跡を外す。順序を逆にすると、その隙に RecordingReconciler が
            // 「処理中でないのに RECORDING のまま＝置き去り」と誤判定してしまう
            activeVideoJobs.release(videoId);
        }
    }

    /**
     * プロセスの出力を読み切って終了を待ち、結果を録画履歴に反映する。
     *
     * @param process     起動済みのダウンロードプロセス
     * @param recordingId 録画履歴の主キー
     * @param videoId     対象の動画 ID
     * @param outputFile  完成予定のファイルのパス
     */
    private void runToCompletion(Process process, Long recordingId, String videoId, Path outputFile) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[yt-dlp] {}", line);
            }
        } catch (IOException e) {
            log.warn("ダウンロードプロセスの出力読み取り中にエラーが発生しました: video={}", videoId, e);
        }

        try {
            recordOutcome(recordingId, videoId, outputFile, process.waitFor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ダウンロードの完了待ちが中断されました: video={}", videoId);
            // 中断された場合も、既にファイルが出来ていれば成功として扱う
            recordOutcome(recordingId, videoId, outputFile, null);
        }
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
