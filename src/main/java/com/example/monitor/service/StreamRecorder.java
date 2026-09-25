package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.notification.DiscordNotifier;
import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import com.example.monitor.util.DiskSpaceUtils;
import com.example.monitor.util.ProcessTermination;
import com.example.monitor.util.RecordingActivity;
import com.example.monitor.util.YtDlpFormatSelector;
import com.example.monitor.util.YtDlpLogFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * 録画は配信時間ぶん（数時間に及ぶこともある）ブロッキングするため、仮想スレッドで終了を待つ。
 * yt-dlp の出力は JVM へのパイプではなく {@code logs/yt-dlp/<動画ID>.log} へ直接書かせている
 * （{@link ProcessLauncher#launch(List, Path)}）。そのため {@code bin/service.sh restart} などで
 * JVM を止めても、yt-dlp は独立した OS プロセスとして配信の最後まで録り、映像と音声の結合まで終える。
 *
 * <p><b>パイプにしていたときは、再起動で録画が壊れた（実際に発生した）。</b>JVM が止まると
 * パイプの読み手がいなくなり、yt-dlp は次に出力した時点で BrokenPipeError になる。
 * {@code --live-from-start} の映像側のダウンロードとメインの処理がそこで終わり
 * （音声側のスレッドだけが取り続けた）、映像は再起動の直後で止まり、最後の結合も行われなかった。
 *
 * <p>再起動すると {@link ActiveVideoJobs} の予約と完了を待つ仮想スレッドは失われ
 * （{@link #isRecording(String)} は録画中のプロセスについても {@code false} を返すようになる）、
 * 録画履歴の完了・失敗を記録する者がいなくなる。これは {@link RecordingReconciler} が
 * 完成ファイルの有無で補正する。
 *
 * <p>出力が止まったまま終わらない yt-dlp は {@link #awaitExit} が子孫ごと止め、既存の救済・記録へ流す
 * （止めないと {@code RECORDING} が残り続け、その動画 ID は {@link ActiveVideoJobs} に押さえられたまま録り直せない）。
 * 再起動後に残った yt-dlp（追跡する仮想スレッドが無いもの）は対象外。実際に固まった例が出てから作る。
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
 * そのため判断材料は「再生できるファイルがあるか」（{@link RecordingSalvager#ensurePlayable(Path)}）だけとし、
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

    private final DiscordNotifier discordNotifier;

    /**
     * 空き容量の逼迫を管理者へ知らせ済みか。下回った状態が続く間は 1 回だけ知らせるために使う。
     *
     * <p>巡回（2 分ごと）のたびに録画を試みて弾かれるので、毎回送ると通知が溢れる。
     * 空き容量の確認を通ったときに戻し、再び下回ったら改めて知らせる。
     * 送信に失敗しても戻さない（Webhook の設定ミスのような直らない失敗で毎巡回送り続けないため）。
     * 知らせ済みかを DB に持たないので、アプリを再起動すると下回ったままでも 1 回送り直す。
     */
    private final AtomicBoolean lowDiskAlerted = new AtomicBoolean(false);

    /**
     * 録画を始めるのに必要な空き容量（GB）。これを下回っていれば録画を始めない。
     * 録画・H2（{@code data/}）・ログが同じファイルシステムにあり、満杯になると録画が壊れるだけでなく
     * H2 の書き込みが失敗して監視・通知まで止まるため。
     *
     * <p><b>{@link MonitorProperties.RecordingProperties} に入れていない。</b>record にフィールドを
     * 足すと正準コンストラクタが変わり、それを直接呼んでいるテスト 10 ファイルがコンパイルエラーになるため。
     * final でないフィールドなので {@code @RequiredArgsConstructor} のコンストラクタも変わらない。
     *
     * <p>初期値を 0（確認しない）にしているのは、Spring を通さずに組み立てるテストが、
     * 実行した機械の空き容量に左右されないようにするため。
     */
    @Value("${monitor.recording.min-free-gb:20}")
    private long minFreeGb = 0;

    /**
     * 録画の出力（{@code {動画ID}.*} と yt-dlp のログ）がこの分数だけ更新されなければ、
     * 固まったとみなして録画プロセスを止める。
     *
     * <p><b>30 分と長めに取っている。</b>正常な録画では出力が数秒おきに更新されるので、短くても見分けは付く。
     * しかし配信者側の回線断で断片が止まっている間に誤って止めると、{@code lastRecordedVideoId} が
     * 更新済みのためその配信の残りを録れない。止めるのが遅れても、{@code RECORDING} の表示が長く残るだけで済む。
     * 損が大きいのは誤って止める方なので、遅めに倒している。
     *
     * <p>{@link MonitorProperties.RecordingProperties} に入れない理由は {@link #minFreeGb} と同じ。
     */
    @Value("${monitor.recording.stall-minutes:30}")
    private long stallMinutes = 30;

    /**
     * 録画プロセスの待機の結果。
     *
     * @param exitCode {@code yt-dlp} の終了コード。待機が中断された場合は {@code null}
     * @param stalled  出力が止まっていたためこちらから止めた場合 {@code true}
     */
    private record ExitResult(Integer exitCode, boolean stalled) {
    }

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
     * 同じ出力先に別プロセスが起動しうる（レビューで指摘された。起こりうる経路）。
     *
     * <p><b>空き容量が {@link #minFreeGb} を下回っていれば起動しない。</b>このとき FAILED を記録しない。
     * {@code false} を返せば呼び出し元は {@code lastRecordedVideoId} を更新せず、次の巡回で再び試みるので、
     * 空きが戻れば録画が始まる。FAILED を記録すると巡回のたびに FAILED の行が増える。
     * 空き容量を<b>読めなかった</b>ときは今までどおり起動する。判定できないことを「満杯」と扱うと、
     * 容量の取得だけが壊れた環境で録画が一切始まらなくなるため（「配信していない」と
     * 「判定できなかった」を区別するのと同じ考え方）。
     *
     * @param channel  録画対象のチャンネル
     * @param watchUrl 録画対象の視聴 URL。形式がプラットフォームごとに異なるため、
     *                 検知結果（{@link com.example.monitor.dto.LiveStreamDetection#watchUrl()}）が運んだものを受け取る
     * @param videoId  録画対象の動画 ID
     * @param title    録画開始時点での配信タイトル。録画一覧画面に表示する
     * @return プロセスの起動に成功した場合（既に録画中の場合を含む） {@code true}。
     *         {@code yt-dlp} が見つからない等で起動に失敗した場合と、空き容量がしきい値を下回る場合は {@code false}
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
            DiskSpaceUtils.Capacity disk = DiskSpaceUtils.read(Path.of(monitorProperties.recording().directory()));
            if (minFreeGb > 0 && disk.error() == null && disk.usableBytes() != null
                    && disk.usableBytes() < minFreeGb * 1024L * 1024 * 1024) {
                // return は try の中なので、予約の解放は finally 節が行う
                log.warn("空き容量がしきい値を下回っているため録画を始めません: channel={}, video={}, 空き={}GB, しきい値={}GB",
                        channel.getChannelName(), videoId, disk.usableBytes() / (1024L * 1024 * 1024), minFreeGb);
                if (lowDiskAlerted.compareAndSet(false, true)) {
                    try {
                        discordNotifier.sendAdminAlert("空き容量が " + disk.usableBytes() / (1024L * 1024 * 1024)
                                + "GB（しきい値 " + minFreeGb + "GB）を下回ったため、録画を始めていません。");
                    } catch (RuntimeException e) {
                        // 通知の失敗で録画の判断（false を返す）を変えない
                        log.warn("空き容量の通知を送れませんでした", e);
                    }
                }
                return false;
            }
            lowDiskAlerted.set(false);

            Path outputDirectory = Path.of(monitorProperties.recording().directory(), channel.getYoutubeChannelId());
            try {
                Files.createDirectories(outputDirectory);
            } catch (IOException e) {
                log.error("録画用ディレクトリの作成に失敗しました: channel={}, directory={}",
                        channel.getYoutubeChannelId(), outputDirectory, e);
                return false;
            }

            List<String> command = buildCommand(watchUrl, videoId, outputDirectory, true);
            List<String> fallbackCommand = buildCommand(watchUrl, videoId, outputDirectory, false);
            Path outputFile = outputDirectory.resolve(videoId + ".mp4");
            String relativeFilePath = channel.getYoutubeChannelId() + "/" + videoId + ".mp4";

            Process process;
            try {
                process = processLauncher.launch(command, YtDlpLogFile.of(videoId));
            } catch (IOException e) {
                log.error("録画プロセスの起動に失敗しました（yt-dlp が無いか、出力先のログファイルを作れない可能性があります）: "
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
                    .start(() -> awaitCompletion(process, channel, videoId, recording.getId(), outputFile,
                            fallbackCommand, mdcChannelId));

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
     * <p><b>{@code fromStart} を外せるようにしている理由。</b>Twitch では {@code --live-from-start} を
     * 付けると配信のアーカイブ（VOD）経由で最初から取ろうとするため、アーカイブがサブスク限定の
     * チャンネルでは配信自体は誰でも見られるのに約 1 秒で失敗する（実際に発生した）。
     * そのときに「今の時点から」録り直すためのコマンドを作るのに使う（{@link #awaitCompletion} 参照）。
     *
     * <p><b>{@code --no-progress} を付ける理由。</b>進捗行は出力のほとんどを占める（チャンネルログへ
     * 流していたときは 72,041 行中 71,647 行、1 日 10〜25MB）。ローテーションの無い録画ごとの
     * ログファイルへそのまま流すと、1 本で数十 MB になるため。
     *
     * @param watchUrl        録画対象の視聴 URL
     * @param videoId         録画対象の動画 ID
     * @param outputDirectory 保存先ディレクトリ
     * @param fromStart       {@code --live-from-start} を付けるなら {@code true}
     * @return {@code yt-dlp} 実行コマンド
     */
    private List<String> buildCommand(String watchUrl, String videoId, Path outputDirectory, boolean fromStart) {
        String outputTemplate = outputDirectory.resolve(videoId + ".%(ext)s").toString();
        String formatSelector = YtDlpFormatSelector.of(monitorProperties.recording().maxHeight());

        List<String> command = new ArrayList<>();
        command.add("yt-dlp");
        if (fromStart) {
            command.add("--live-from-start");
        }
        command.add("--no-part");
        command.add("--no-progress");
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
     * 録画プロセスの終了を待って結果を履歴に残す。1 回目が再生できるファイルを残さずに終わったら、
     * {@code fallbackCommand} で「今の時点から」もう 1 回だけ録り直す。
     *
     * <p><b>録り直すかは失敗の文言ではなく「完成ファイルが無い」ことで決める。</b>
     * yt-dlp のエラー文言は版ごとに変わりうるため。Twitch でアーカイブがサブスク限定のチャンネルは
     * {@code --live-from-start} だと必ず失敗するが、配信そのものは録れる（{@link #buildCommand} 参照）。
     * YouTube でも同じ動きになるが、1 回目で失敗するのはまれで、もう 1 回試しても害は無い。
     *
     * <p>録り直しの間も録画履歴は {@code RECORDING} のまま、動画IDの予約も保持し続ける。
     * 途中で {@code FAILED} にしたり予約を外したりすると、録り直し中の配信を
     * 次の巡回が二重に録画したり、{@link RecordingReconciler} が置き去りと誤判定したりするため。
     * 待機が中断された（アプリ停止など）場合は録り直さない。
     *
     * <p>MDC はスレッドローカルなため、このメソッドは呼び出し元（監視ループのスレッド）とは
     * 別スレッドで動く仮想スレッドの中から呼ばれる。呼び出し元が設定していた MDC の値を
     * 引数で受け取って改めて設定しないと、チャンネル別ログへの振り分けが効かなくなる。
     *
     * @param process         起動済みの録画プロセス
     * @param channel         録画対象のチャンネル
     * @param videoId         録画対象の動画 ID
     * @param recordingId     {@link RecordingHistoryService#recordStart}で発行された録画履歴の主キー
     * @param outputFile      完成予定の録画ファイルのパス（{@code --merge-output-format mp4}指定により確定済み）
     * @param fallbackCommand 1 回目が失敗したときに使う録り直し用のコマンド。録り直さないなら {@code null}
     * @param mdcChannelId    呼び出し元スレッドで設定されていた MDC の channelId（未設定なら {@code null}）
     */
    void awaitCompletion(Process process, MonitoredChannel channel, String videoId, Long recordingId,
                          Path outputFile, List<String> fallbackCommand, String mdcChannelId) {
        if (mdcChannelId != null) {
            MDC.put(MDC_CHANNEL_ID_KEY, mdcChannelId);
        }

        try {
            ExitResult exit = awaitExit(process, channel, videoId, outputFile.getParent());
            // プロセスは既に終了しているので、書き込み中のファイルを壊す心配なく詰め替えられる
            SalvageOutcome salvage = recordingSalvager.ensurePlayable(outputFile);
            boolean resumedMidway = false;

            // 固まって止めたときは録り直さない。配信が終わっていれば「今の時点から」は失敗するか、
            // 終わった配信のアーカイブ全体を落とし始める。配信中でも同じ固まり方を繰り返しうる
            if (!salvage.isPlayable() && fallbackCommand != null && !exit.stalled()
                    && !Thread.currentThread().isInterrupted()) {
                log.warn("最初からの録画に失敗したため、今の時点から録画し直します: channel={}, video={}, exitCode={}",
                        channel.getChannelName(), videoId, exit.exitCode());
                try {
                    Process retry = processLauncher.launch(fallbackCommand, YtDlpLogFile.of(videoId));
                    resumedMidway = true;
                    exit = awaitExit(retry, channel, videoId, outputFile.getParent());
                    salvage = recordingSalvager.ensurePlayable(outputFile);
                } catch (IOException e) {
                    log.error("録り直しの録画プロセスの起動に失敗しました: channel={}, video={}",
                            channel.getChannelName(), videoId, e);
                }
            }

            recordOutcome(recordingId, channel, videoId, salvage, resumedMidway, exit.exitCode());
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
     * 録画プロセスの終了を待つ。出力が {@link #stallMinutes} 分止まっていたら、子孫ごと止める。
     *
     * <p>出力はファイルへ向けているため（クラスの JavaDoc「プロセスの生存期間」参照）、ここでは読まない。
     * 1 分ごとに {@link RecordingActivity#lastModified(Path, String)} を見る。待機を始めた時刻を下限にするのは、
     * 起動直後でまだ何も書かれていないときと、録り直しで待ち直したときに、古い時刻で誤って止めないため。
     * 更新時刻を読めなかったときは止めない（「判定できなかった」を「固まった」と扱わない）。
     *
     * @param process         起動済みの録画プロセス
     * @param channel         録画対象のチャンネル（ログ・通知用）
     * @param videoId         録画対象の動画 ID
     * @param outputDirectory 録画フォルダ
     * @return 終了コード（待機が中断された場合は {@code null}。割り込み状態は立て直す）と、こちらから止めたか
     */
    private ExitResult awaitExit(Process process, MonitoredChannel channel, String videoId, Path outputDirectory) {
        Instant watchStart = Instant.now();
        try {
            while (!process.waitFor(1, TimeUnit.MINUTES)) {
                Instant lastActivity;
                try {
                    Instant modified = RecordingActivity.lastModified(outputDirectory, videoId);
                    lastActivity = modified.isAfter(watchStart) ? modified : watchStart;
                } catch (IOException e) {
                    log.warn("録画の出力の更新時刻を読めなかったため、固まっているかの判定を見送ります: video={}", videoId, e);
                    continue;
                }
                if (Duration.between(lastActivity, Instant.now()).toMinutes() < stallMinutes) {
                    continue;
                }

                log.warn("録画の出力が {}分間止まっているため、録画プロセスを止めます: channel={}, video={}, 最終更新={}",
                        stallMinutes, channel.getChannelName(), videoId, lastActivity);
                if (ProcessTermination.terminateTreeAndAwait(process.toHandle(), Duration.ofSeconds(30))) {
                    Thread.currentThread().interrupt();
                    return new ExitResult(null, true);
                }
                try {
                    discordNotifier.sendAdminAlert("録画の出力が " + stallMinutes + " 分間止まっていたため、録画プロセスを止めました: "
                            + channel.getChannelName() + "（" + videoId + "）");
                } catch (RuntimeException e) {
                    // 通知の失敗で録画の記録を妨げない
                    log.warn("録画プロセスを止めたことを管理者へ通知できませんでした", e);
                }
                return new ExitResult(process.waitFor(), true);
            }
            return new ExitResult(process.exitValue(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断された場合も、既にファイルが出来ていれば成功として扱う（呼び出し側で判定する）
            log.warn("録画の完了待ちが中断されました: video={}", videoId);
            return new ExitResult(null, false);
        }
    }

    /**
     * 録画の成否を確定させて履歴に記録する。
     *
     * <p><b>判断材料は完成ファイルの有無だけで、{@code yt-dlp} の終了コードは使わない。</b>
     * 理由はクラスの JavaDoc を参照。終了コードはログの文面を変えるためだけに使う。
     *
     * <p><b>録り直しで録れたものも {@code PARTIAL} にする。</b>配信の最初からは録れていないため、
     * {@code COMPLETED} にすると一覧で区別できなくなる。{@code PARTIAL} は本来
     * {@link RecordingSalvager} が「途中で止まった録画を詰め替えた」ことを表すため、
     * 「最初が欠けている」と「最後が欠けている」の 2 つの意味が混ざる。どちらも
     * 「再生できるが配信の全体ではない」点は同じなので、状態を増やさずに同じ扱いにしている
     * （列挙子を増やすと既存 DB で全更新が失敗する落とし穴もある）。
     *
     * @param recordingId   録画履歴の主キー
     * @param channel       録画対象のチャンネル
     * @param videoId       録画対象の動画 ID
     * @param salvage       録画プロセス終了後に {@link RecordingSalvager#ensurePlayable(Path)} で確かめた結果
     * @param resumedMidway 配信の途中から録り直したなら {@code true}
     * @param exitCode      {@code yt-dlp} の終了コード。待機が中断されて取得できなかった場合は {@code null}
     */
    private void recordOutcome(Long recordingId, MonitoredChannel channel, String videoId,
                               SalvageOutcome salvage, boolean resumedMidway, Integer exitCode) {
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

        if (resumedMidway) {
            recordingHistoryService.markPartial(recordingId, salvage.fileSizeBytes());
            log.info("配信の途中から録り直した録画を「途中まで」として記録します: "
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
