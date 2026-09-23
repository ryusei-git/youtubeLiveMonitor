package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import com.example.monitor.util.ProcessTermination;
import com.example.monitor.util.YtDlpFormatSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 配信を {@code yt-dlp} で録画する。
 *
 * <h2>なぜ Java 製のライブラリを使わないのか</h2>
 * ライブ配信の録画は「配信が終わるまで、数秒ごとに増え続けるセグメントを継続的に取得し続ける」
 * 処理が必要で、これは YouTube 側の頻繁な仕様変更に追従し続ける継続的なリバースエンジニアリングを
 * 要する。調査の結果、これができる Java 製・Python 非依存のライブラリは存在しなかった
 * （既存の Java ライブラリはメタデータ取得や通常動画のダウンロードに留まる）。
 * そのため {@code yt-dlp} を外部プロセスとして起動する方式を採用している。
 * アプリのソースコード自体は 100% Java のまま。
 *
 * <h2>プロセスの生存期間</h2>
 * 録画は配信時間ぶん（数時間に及ぶこともある）ブロッキングするため、仮想スレッドで
 * 出力の読み取りと終了待ちを行う。アプリを再起動しても yt-dlp プロセス自体は
 * 独立した OS プロセスとして動き続ける（{@code bin/service.sh stop} で JVM を止めても
 * 子プロセスの yt-dlp までは止めない設計）。ただしアプリ再起動後は
 * {@link ActiveVideoJobs} の予約が失われるため、{@link #isRecording(String)} は
 * 実際には録画中のプロセスについても {@code false} を返すようになる
 * （録画中に再起動すると、その録画は {@link com.example.monitor.entity.Recording.RecordingStatus#RECORDING}
 * のまま完了・失敗の記録が更新されなくなる）。
 *
 * <h2>出力形式を mp4 に固定する理由</h2>
 * {@code --merge-output-format mp4} を指定し、映像・音声のコンテナを常に mp4 に揃えている。
 * 指定しないと yt-dlp が自動選択したコーデックの組み合わせによって最終ファイルが mp4 になるか
 * mkv になるか実行時まで決まらず、(1) 録画開始時点でファイル名を確定できない、
 * (2) ブラウザの {@code <video>} タグでの再生互換性が不安定、という 2 つの問題が出るため。
 *
 * <h2>成否は終了コードではなく「完成ファイルの有無」で決める</h2>
 * <b>{@code yt-dlp} の終了コードを成否の判断に使ってはならない。</b>
 * 長時間のライブ録画では、配信終了間際に最後の数フラグメントが取得できない
 * （{@code ERROR: Did not get any data blocks}）ことが珍しくない。yt-dlp はそれらを
 * スキップして残りを最後までダウンロードし、マージも正常に終えて<b>再生可能なファイルを
 * 完成させたうえで</b>、「途中でエラーがあった」ことを理由に終了コード 1 を返す。
 *
 * <p>実際にこれが起きた（1 時間 30 分・736MB の正常なファイルが出来ていたのに、
 * 終了コードだけを見て「失敗」と記録され、画面から再生できなくなった）。
 * 数時間ぶんの録画を最後の数秒のために丸ごと失敗扱いにするのは実態に合わない。
 * そのため判断材料は {@link RecordingFileService#sizeIfExists(Path)} だけとし、
 * 終了コードはログの文面を変えるためにしか使わない。
 * {@link RecordingReconciler} が置き去りの録画履歴を補正するときも同じ基準を使う。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreamRecorder {

    /** Logback の SiftingAppender がログの振り分け先を決めるために参照する MDC のキー。 */
    private static final String MDC_CHANNEL_ID_KEY = "channelId";

    private final MonitorProperties monitorProperties;
    private final ProcessLauncher processLauncher;
    private final RecordingHistoryService recordingHistoryService;
    private final RecordingSalvager recordingSalvager;

    /**
     * 録画中の動画IDの予約。同じ配信を二重に録画しないために使う。
     *
     * <p>プロセスそのものは持たない。必要なのは「この動画IDを録画中か」だけで、
     * 完了待ちは起動した仮想スレッドが自分の {@link Process} を握っているため。
     *
     * <p><b>手動ダウンロード（{@link VideoDownloadService}）と予約を共有している。</b>
     * 判定時は配信していなかったが、その直後に配信が始まって自動録画が動く、という経路が
     * あるため、それぞれが別々の集合を持つと、同じ動画IDに対して両方がほぼ同時に
     * {@code yt-dlp} を起動できてしまう（詳細は {@link ActiveVideoJobs} の JavaDoc 参照）。
     */
    private final ActiveVideoJobs activeVideoJobs;

    /**
     * 配信の録画を開始する。既にこの動画IDを録画中であれば何もせず成功として扱う。
     *
     * <p>録画は配信終了まで続く長時間のバックグラウンド処理のため、このメソッド自体は
     * プロセスを起動したらすぐに返る（録画の完了を待たない）。
     *
     * <p><b>「録画中か」の確認と登録は 1 回の操作で行う。</b>
     * 確認してから登録するまでに隙間があると、2 つのスレッドが同時に同じ動画IDで入ったとき
     * <b>どちらも「まだ録画していない」と判断して {@code yt-dlp} を二重に起動</b>してしまう。
     * 同じ出力先へ 2 つのプロセスが書き込むことになり、録画そのものが壊れる。
     * 現状これは {@code LiveStreamPollingScheduler} 側の排他でも防がれているが、
     * <b>その保証は別クラスにあり、巡回の起動経路が増えれば破れる</b>。
     * そのためこのクラス自身で、{@link ActiveVideoJobs#reserve(String)} という
     * 1 回の操作で成り立たせている（{@link VideoDownloadService} と共有しているため、
     * 手動ダウンロード側からの同時開始も同じ操作で防げる）。
     *
     * <p>登録は<b>起動と後始末の手配まで済んだ場合だけ残す</b>。失敗したまま登録が残ると、
     * その動画IDは（登録を消す者がいないため）以降二度と録画できなくなる。
     * {@link RecordingHistoryService#recordStart} が例外を投げた場合は、
     * {@link ProcessTermination#destroyForciblyAndAwait(Process)} で起動済みのプロセスを
     * 停止・終了確認してから例外を投げ直す。ここで止めずに登録だけ外すと、次の巡回・再試行で
     * 同じ出力先に別プロセスが起動しうる（実際に起きた指摘）。
     *
     * @param channel  録画対象のチャンネル
     * @param watchUrl 録画対象の視聴 URL。プラットフォームごとに形式が異なるため
     *                 呼び出し側（{@code StreamPlatform.watchUrl}）が組み立てたものを受け取る
     * @param videoId  録画対象の動画 ID
     * @param title    録画開始時点での配信タイトル。録画一覧画面に表示する
     * @return プロセスの起動に成功した場合（既に録画中の場合を含む） {@code true}。
     *         {@code yt-dlp} が見つからない等で起動に失敗した場合は {@code false}
     * @throws RuntimeException {@link RecordingHistoryService#recordStart} が失敗した場合。
     *                          起動済みのプロセスは停止済みで、予約も解放済みの状態で伝播する
     */
    public boolean startRecording(MonitoredChannel channel, String watchUrl, String videoId, String title) {
        // 確認と登録を分けると、その隙間に別スレッドが入り込んで二重起動しうる（上記 JavaDoc 参照）
        if (!activeVideoJobs.reserve(videoId)) {
            log.debug("既に録画中またはダウンロード中のためスキップします: video={}", videoId);
            return true;
        }

        boolean started = false;
        try {
            Path outputDirectory = Path.of(monitorProperties.recording().directory(), channel.getYoutubeChannelId());
            try {
                Files.createDirectories(outputDirectory);
            } catch (IOException e) {
                log.error("録画用ディレクトリの作成に失敗しました: channel={}, directory={}",
                        channel.getYoutubeChannelId(), outputDirectory, e);
                return false;
            }

            List<String> command = buildCommand(watchUrl, videoId, outputDirectory);
            Path outputFile = outputDirectory.resolve(videoId + ".mp4");
            String relativeFilePath = channel.getYoutubeChannelId() + "/" + videoId + ".mp4";

            Process process;
            try {
                process = processLauncher.launch(command);
            } catch (IOException e) {
                log.error("録画プロセスの起動に失敗しました（yt-dlpがインストールされていない可能性があります）: "
                        + "channel={}, video={}", channel.getChannelName(), videoId, e);
                return false;
            }
            log.info("録画を開始しました: channel={}, video={}, directory={}",
                    channel.getChannelName(), videoId, outputDirectory);

            Recording recording;
            try {
                recording = recordingHistoryService.recordStart(channel, videoId, title, relativeFilePath);
            } catch (RuntimeException e) {
                // 起動済みのプロセスを放置すると、次の巡回・再試行で同じ出力先に
                // 別プロセスが起動しうる。実際に終了したことを確認してから予約を解放する（finally節）
                log.error("録画履歴の登録に失敗したため、起動済みの録画プロセスを停止します: "
                        + "channel={}, video={}", channel.getChannelName(), videoId, e);
                if (ProcessTermination.destroyForciblyAndAwait(process)) {
                    Thread.currentThread().interrupt();
                }
                throw e;
            }

            String mdcChannelId = MDC.get(MDC_CHANNEL_ID_KEY);
            Thread.ofVirtual()
                    .name("recording-" + videoId)
                    .start(() -> awaitCompletion(process, channel, videoId, recording.getId(), outputFile, mdcChannelId));

            // ここまで来て初めて、登録を外す者（awaitCompletion）が存在する状態になる
            started = true;
            return true;
        } finally {
            if (!started) {
                // 登録を残したままにすると、この動画IDは以降永久に録画できなくなる
                activeVideoJobs.release(videoId);
            }
        }
    }

    /**
     * 指定した動画を現在録画中かどうかを返す。
     *
     * @param videoId 確認したい動画 ID
     * @return 録画中であれば {@code true}
     */
    public boolean isRecording(String videoId) {
        return activeVideoJobs.isActive(videoId);
    }

    /**
     * {@code yt-dlp} に渡すコマンドを組み立てる。
     *
     * <p>{@code --live-from-start} により、対応していれば配信の実際の開始時点から録画する
     * （指定しないと「録画を開始した瞬間」からしか録れない）。画質・音質は落としたくないという
     * 要望があるため、{@code maxHeight} が未設定（{@code 0} 以下）の場合は解像度フィルタを付けず、
     * 配信で提供される最高画質・音質のトラックをそのまま録画する。
     * {@code --merge-output-format mp4} で最終ファイルの拡張子を固定している理由はクラスの JavaDoc を参照。
     *
     * @param channel         録画対象のチャンネル
     * @param videoId         録画対象の動画 ID
     * @param outputDirectory 保存先ディレクトリ
     * @return {@code yt-dlp} 実行コマンド
     */
    private List<String> buildCommand(String watchUrl, String videoId, Path outputDirectory) {
        String outputTemplate = outputDirectory.resolve(videoId + ".%(ext)s").toString();
        String formatSelector = YtDlpFormatSelector.of(monitorProperties.recording().maxHeight());

        List<String> command = new ArrayList<>();
        command.add("yt-dlp");
        command.add("--live-from-start");
        command.add("--no-part");
        command.add("--merge-output-format");
        command.add("mp4");
        command.add("-f");
        command.add(formatSelector);
        command.add("-o");
        command.add(outputTemplate);
        command.add(watchUrl);
        return command;
    }

    /**
     * 録画プロセスの出力を読み切り、終了を待って結果をログに残す。
     *
     * <p>MDC はスレッドローカルなため、このメソッドは呼び出し元（監視ループのスレッド）とは
     * 別スレッドで動く仮想スレッドの中から呼ばれる。呼び出し元が設定していた MDC の値を
     * 引数で受け取って改めて設定しないと、チャンネル別ログへの振り分けが効かなくなる。
     *
     * @param process      起動済みの録画プロセス
     * @param channel      録画対象のチャンネル
     * @param videoId      録画対象の動画 ID
     * @param recordingId  {@link RecordingHistoryService#recordStart}で発行された録画履歴の主キー
     * @param outputFile   完成予定の録画ファイルのパス（{@code --merge-output-format mp4}指定により確定済み）
     * @param mdcChannelId 呼び出し元スレッドで設定されていた MDC の channelId（未設定なら {@code null}）
     */
    void awaitCompletion(Process process, MonitoredChannel channel, String videoId, Long recordingId,
                          Path outputFile, String mdcChannelId) {
        if (mdcChannelId != null) {
            MDC.put(MDC_CHANNEL_ID_KEY, mdcChannelId);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[yt-dlp] {}", line);
            }
        } catch (IOException e) {
            log.warn("録画プロセスの出力読み取り中にエラーが発生しました: video={}", videoId, e);
        }

        try {
            int exitCode = process.waitFor();
            recordOutcome(recordingId, channel, videoId, outputFile, exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("録画の完了待ちが中断されました: video={}", videoId);
            // 中断された場合も、既にファイルが出来ていれば成功として扱う
            recordOutcome(recordingId, channel, videoId, outputFile, null);
        } finally {
            // 結果を記録し終えてから追跡を外す。順序を逆にすると、その隙に
            // RecordingReconciler が「追跡されていないのに RECORDING のまま＝置き去り」と
            // 誤判定してしまう
            activeVideoJobs.release(videoId);
            if (mdcChannelId != null) {
                MDC.remove(MDC_CHANNEL_ID_KEY);
            }
        }
    }

    /**
     * 録画の成否を確定させて履歴に記録する。
     *
     * <p><b>判断材料は完成ファイルの有無だけで、{@code yt-dlp} の終了コードは使わない。</b>
     * 理由はクラスの JavaDoc を参照。終了コードはログの文面を変えるためだけに使う。
     *
     * @param recordingId 録画履歴の主キー
     * @param channel     録画対象のチャンネル
     * @param videoId     録画対象の動画 ID
     * @param outputFile  完成予定の録画ファイルのパス
     * @param exitCode    {@code yt-dlp} の終了コード。待機が中断されて取得できなかった場合は {@code null}
     */
    private void recordOutcome(Long recordingId, MonitoredChannel channel, String videoId,
                               Path outputFile, Integer exitCode) {
        // プロセスは既に終了しているので、書き込み中のファイルを壊す心配なく詰め替えられる
        SalvageOutcome salvage = recordingSalvager.ensurePlayable(outputFile);

        if (!salvage.isPlayable()) {
            log.warn("再生できる録画ファイルを用意できなかったため失敗として記録します: "
                            + "channel={}, video={}, exitCode={}",
                    channel.getChannelName(), videoId, exitCode);
            recordingHistoryService.markFailed(recordingId);
            return;
        }

        if (salvage.status() == SalvageStatus.SALVAGED) {
            // 配信の最後までは録れていない。完了と同じ扱いにすると、短く終わった理由が分からなくなる
            recordingHistoryService.markPartial(recordingId, salvage.fileSizeBytes());
            log.info("配信の途中で録画が終わったため、そこまでの内容を再生できる形にして記録します: "
                            + "channel={}, video={}, size={}, exitCode={}",
                    channel.getChannelName(), videoId, salvage.fileSizeBytes(), exitCode);
            return;
        }

        recordingHistoryService.markCompleted(recordingId, salvage.fileSizeBytes());
        if (exitCode != null && exitCode == 0) {
            log.info("録画が正常に終了しました: channel={}, video={}, size={}",
                    channel.getChannelName(), videoId, salvage.fileSizeBytes());
        } else {
            log.info("一部に失敗がありましたが録画ファイルは完成したため完了として記録します: "
                            + "channel={}, video={}, size={}, exitCode={}",
                    channel.getChannelName(), videoId, salvage.fileSizeBytes(), exitCode);
        }
    }
}
