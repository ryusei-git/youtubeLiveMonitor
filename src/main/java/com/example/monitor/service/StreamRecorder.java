package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.notification.DiscordNotifier;
import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import com.example.monitor.util.DiskSpaceUtils;
import com.example.monitor.util.FileNameUtils;
import com.example.monitor.util.ProcessTermination;
import com.example.monitor.util.RecordingActivity;
import com.example.monitor.util.RequestContext;
import com.example.monitor.util.YtDlpFormatSelector;
import com.example.monitor.util.YtDlpJsRuntime;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>管理画面から止めた録画（{@link #stopRecording(Long)}）も同じく子孫ごと止め、録り直さずに救済・記録へ流す。
 * こちらは再起動後に残った yt-dlp も OS から探して止める（記録は {@link RecordingReconciler} が補正する）。
 *
 * <p><b>録画中に空き容量が {@link DiskSpaceUtils#reserveBytes(long)}（録画を始めるしきい値の 1/4）を割ったら、
 * {@link #awaitExit} が録画プロセスを止めて既存の救済・記録へ流す。</b>0 まで減ると録画が壊れるだけでなく、
 * 同じファイルシステムにある H2 の書き込みが失敗して監視・通知まで止まるので、下限で止める方が損が小さい。
 * 各録画の {@link #awaitExit} が 1 分ごとに独立に見るので、そのとき録画中の録画はすべて 1 分ほどで止まる
 * （1 本だけ止めても残りがすぐに下限を割るため、どれから止めるかの優先順位は作らない）。
 * yt-dlp が結合の最中でも区別せずに止める（見逃しても結合が空きを使い切るのは防げない。止めても元の断片は残るので、
 * 後始末で直せる）。止めた録画は、固まって止めた録画と同じく録り直さない。
 *
 * <p><b>プロセスが終わったことを確かめられたら、止めた理由を問わず {@code {動画ID}.temp.mp4} を消す</b>
 * （自然に終わった・固まって止めた・空き容量で止めた、のすべて。録り直しの後も同じ）。yt-dlp は結合に失敗すると
 * この書きかけを消さずに終わり、空きを 0 まで使い切った大きさのまま空きを塞ぎ続けるため
 * （{@link #deleteMergeLeftover} 参照）。
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

    /**
     * 録画の失敗を知らせるときに添える、yt-dlp の出力の末尾のバイト数。
     * Discord の埋め込みの本文は 4096 文字までなので、定型の文面と合わせても収まる大きさにしている。
     */
    private static final int FAILURE_ALERT_LOG_TAIL_BYTES = 1500;

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
     * 巡回が「まだ配信中」と確かめてから録り直す回数の上限。1 回目の直後にその場で行う録り直しは数えない。
     *
     * <p>上限があるのは、待機所の誤検知のような直らない失敗で、配信中と判定され続ける限り
     * 録り直し続けないため（通知の再試行に上限があるのと同じ考え方）。
     */
    private static final int MAX_LIVE_RETRIES = 2;

    /**
     * 巡回の合図を待つ長さ。巡回の間隔（{@code monitor.youtube.interval-seconds}）の何倍か。
     *
     * <p>1 倍だと、巡回 1 周が間隔より長引いたときや、その回の判定が失敗（DETECTION_FAILED）したときに
     * 配信中でも諦めてしまう。巡回は前の周が終わってから間隔を空けて始まるので、同じチャンネルを見るまでの
     * 時間は「1 周の所要時間 + 間隔」になる。
     */
    private static final int LIVE_CONFIRMATION_WAIT_CYCLES = 3;

    /**
     * 録り直しの合図を待っている録画スレッド。キーは動画 ID。
     *
     * <p>同じ動画 ID を録画できるのは {@link ActiveVideoJobs} の予約を取った 1 本だけなので、
     * 1 つの動画 ID を待つスレッドは同時に 1 本しかない。
     * 管理画面から止めたとき（{@link #stopRecording(Long)}）と、削除したチャンネルの録画が
     * 予約を押さえたまま待っているとき（{@link #startRecording}）も、待ちを解くために完了させる。
     */
    private final Map<String, CompletableFuture<Void>> liveConfirmations = new ConcurrentHashMap<>();

    /**
     * このクラスが録画スレッドを起動した録画の、動画 ID から録画履歴の主キーへの対応。
     * 予約（{@link ActiveVideoJobs}）を押さえているのが削除したチャンネルの録画かを、
     * {@link #startRecording} が行の有無で見分けるために使う（理由はそのメソッドの JavaDoc）。
     *
     * <p><b>待ちを解くのを、削除する側（{@link MonitoredChannelService#remove(Long)}）に
     * 任せていない。</b>CLI の {@code channel remove} は別の JVM で動くのでこの待ちに届かず、
     * 詰め替えの最中や止めた yt-dlp の終了待ちの間は、解く待ちがそもそも無いため。
     * 次に同じ配信を録ろうとした時点で見分ければ、どの削除の経路でも効く。
     *
     * <p>このクラス以外が取った予約（手動ダウンロードなど）は載せない。それらが押さえている
     * ときは、今までどおり「既に録画中」として扱う。
     *
     * <p>録画スレッドを起動する前に置き、{@code awaitCompletion} の終わりで予約を外した
     * <b>後</b>に消す（理由はそこのコメント）。
     */
    private final Map<String, Long> trackedRecordingIds = new ConcurrentHashMap<>();

    /**
     * 管理画面から止めた録画の動画 ID。{@code awaitCompletion} は、ここにある録画を「今の時点から」録り直さない。
     *
     * <p><b>印が要る理由。</b>止めた yt-dlp が再生できるファイルを残さないと、{@code awaitCompletion} は
     * 「最初からの録画に失敗した」と見て {@code fallbackCommand} で録り直し、止めたはずの配信を録り続ける
     * （固まった・空き容量で止めたときに {@code ExitResult.stoppedByUs} で録り直しを避けているのと同じ理由）。
     * 印はプロセスを止める<b>前</b>に付ける。止めた後に付けると、その隙に録り直しが始まりうる。
     *
     * <p>{@code awaitCompletion} の終わりで外す。手動ダウンロード（{@link VideoDownloadService}）を止めたときは
     * 外す者がいないが、同じ動画 ID の録画を始めるとき（{@link #startRecording}）に外すので、新しい録画には効かない。
     */
    private final Set<String> stopRequested = ConcurrentHashMap.newKeySet();

    /**
     * 録画を始められない（yt-dlp を起動できない・録画フォルダを作れない）ことを管理者へ知らせ済みか。
     *
     * <p>{@code false} を返すと呼び出し元は次の巡回で再び試みるので、直らない限り 2 分ごとに失敗する。
     * 毎回送ると通知が溢れるため、{@link #lowDiskAlerted} と同じく 1 回だけ知らせ、起動できたときに戻す。
     * 送信に失敗しても戻さない（理由も {@link #lowDiskAlerted} と同じ）。
     */
    private final AtomicBoolean startFailureAlerted = new AtomicBoolean(false);

    /**
     * 録画を始めるのに必要な空き容量（GB）。これを下回っていれば録画を始めない。
     * 録画・H2（{@code data/}）・ログが同じファイルシステムにあり、満杯になると録画が壊れるだけでなく
     * H2 の書き込みが失敗して監視・通知まで止まるため。
     * 録画中はこの 1/4 を下限にし、割ったら録画を止める（{@link DiskSpaceUtils#reserveBytes(long)}）。
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
     * {@code yt-dlp} に {@code --js-runtimes} で渡す JavaScript のランタイム（空なら付けない）。
     * 理由は {@link YtDlpJsRuntime} を参照。
     *
     * <p>{@link MonitorProperties.RecordingProperties} に入れない理由は {@link #minFreeGb} と同じ。
     */
    @Value("${monitor.recording.js-runtime:}")
    private String jsRuntime = "";

    /**
     * 録画プロセスの待機の結果。
     *
     * @param exitCode    {@code yt-dlp} の終了コード。待機が中断された場合は {@code null}
     * @param stoppedByUs 出力が止まっていた、または空き容量が下限を割ったため、こちらから止めた場合 {@code true}
     */
    private record ExitResult(Integer exitCode, boolean stoppedByUs) {
    }

    /** 管理画面からの停止（{@link #stopRecording(Long)}）を受け付けた結果。 */
    public enum StopOutcome {
        /** 止め始めた。止め終わるまで最大 30 秒かかり、結果は録画一覧の状態で分かる。 */
        STOPPING,
        /** 録画中（{@code RECORDING}）ではない。 */
        NOT_RECORDING,
        /** 録画中の記録だが、止める yt-dlp が無く、このアプリも追跡していない（既に終わり、後始末を待っている）。 */
        NO_PROCESS
    }

    /**
     * 配信の録画を開始する。既にこの動画IDを録画中であれば何もせず成功として扱う。
     * ただし、予約を押さえているのが削除したチャンネルの録画なら失敗として扱う（下記）。
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
     * <p><b>yt-dlp を起動できない・録画フォルダを作れないときは、管理者へ 1 回だけ知らせる。</b>
     * {@code false} を返すと次の巡回で再び試みるので、直るまでは失敗が続く。ログだけでは気付けないため
     * （{@link #startFailureAlerted} 参照）。
     *
     * <p><b>予約を押さえているのが削除したチャンネルの録画（録画履歴の行が消えている）なら
     * {@code false} を返す。</b>録り直しの合図の待ち（既定で最大 6 分）や詰め替えの最中に
     * チャンネルを削除して登録し直すと、予約は古い録画スレッドが押さえたまま残る。
     * ここで {@code true} を返すと、巡回は {@code lastRecordedVideoId} を更新し、古いスレッドは
     * 行が無いので何も録らずに終わるため、その配信は二度と録られない（通知は届くので気付けない）。
     * {@code false} なら、予約が外れた後の巡回で録り始める。合図を待っていれば起こして、
     * 予約を早く外させる。押さえている録画の行は {@link #trackedRecordingIds} で引く。
     *
     * @param channel  録画対象のチャンネル
     * @param watchUrl 録画対象の視聴 URL。形式がプラットフォームごとに異なるため、
     *                 検知結果（{@link com.example.monitor.dto.LiveStreamDetection#watchUrl()}）が運んだものを受け取る
     * @param videoId  録画対象の動画 ID
     * @param title    録画開始時点での配信タイトル。録画一覧画面に表示する
     * @return プロセスの起動に成功した場合（既に録画中の場合を含む） {@code true}。
     *         {@code yt-dlp} が見つからない等で起動に失敗した場合、空き容量がしきい値を
     *         下回る場合、予約を押さえているのが削除したチャンネルの録画の場合は {@code false}
     * @throws RuntimeException {@link RecordingHistoryService#recordStart} が失敗した場合。
     *                          起動済みのプロセスは停止済みで、予約も解放済みの状態で伝播する。
     *                          予約を押さえている録画の行を確かめられなかった場合も投げる
     *                          （{@link RecordingHistoryService#exists}。予約は取っていない）
     */
    public boolean startRecording(MonitoredChannel channel, String watchUrl, String videoId, String title) {
        // 予約を取る前に読む。取れなかった後に読むと、その間に押さえていたスレッドが
        // 予約を外して対応を消し終え、削除したチャンネルの録画だったと分からなくなる
        // （awaitCompletion は予約を外してから対応を消す）
        Long heldRecordingId = trackedRecordingIds.get(videoId);
        // 確認と登録を分けると、その隙間に別スレッドが入り込んで二重起動しうる（上記 JavaDoc 参照）
        if (!activeVideoJobs.reserve(videoId)) {
            if (heldRecordingId != null && !recordingHistoryService.exists(heldRecordingId)) {
                // 押さえているのは、チャンネルの削除で行が消えた録画。true を返すと巡回が
                // lastRecordedVideoId を更新し、登録し直したチャンネルでこの配信を録らなくなる。
                // 合図を待っていれば起こして、予約を早く外させる
                // （起きたスレッドは行が無いのを見て、録り直さずに終わる）
                CompletableFuture<Void> waiting = liveConfirmations.get(videoId);
                if (waiting != null) {
                    waiting.complete(null);
                }
                // 「次の巡回で」とは書かない。詰め替えの最中なら予約が外れるまで巡回のたびにここへ来て、
                // 同じ行が数回続くため
                log.info("削除したチャンネルの録画が予約を押さえているため、予約が外れた後の巡回で録り始めます: "
                        + "channel={}, video={}", channel.getChannelName(), videoId);
                return false;
            }
            log.debug("既に録画中またはダウンロード中のためスキップします: video={}", videoId);
            return true;
        }
        // 前に同じ動画 ID を止めた印が残っていても（手動ダウンロードを止めた場合など）、これから始める録画には効かせない
        stopRequested.remove(videoId);

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
                alertStartFailureOnce("録画フォルダを作れないため、録画を始められません: " + outputDirectory + "（" + e + "）。"
                        + "直るまで巡回のたびに試みますが、この通知は録画を始められるまで再び送りません。");
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
                // getMessage() ではなく例外の種類ごと載せる。logs/yt-dlp/ を作れないときの AccessDeniedException は
                // メッセージがパスだけで、理由が読めないため（録画フォルダの通知と同じ形）
                alertStartFailureOnce("録画プロセス（yt-dlp）を起動できないため、録画を始められません: "
                        + channel.getChannelName() + "（" + videoId + "）。"
                        + "yt-dlp が入っていてサービスの PATH から見えるか、logs/yt-dlp/ に書き込めるかを確かめてください（"
                        + e + "）。直るまで巡回のたびに試みますが、この通知は録画を始められるまで再び送りません。");
                return false;
            }
            log.info("録画を開始しました: channel={}, video={}, directory={}",
                    channel.getChannelName(), videoId, outputDirectory);
            // 直ったので、次に起動できなくなったときに改めて知らせる
            startFailureAlerted.set(false);

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
            // 録画スレッドを起動する前に置く。起動の後に置くと、スレッドが先に終わって
            // 消した後に置くことになり、対応が残り続けうる
            trackedRecordingIds.put(videoId, recording.getId());
            Thread.ofVirtual()
                    .name("recording-" + videoId)
                    .start(() -> awaitCompletion(process, channel, videoId, recording.getId(), outputFile,
                            fallbackCommand, mdcChannelId));

            // ここまで来て初めて、登録を外す者（awaitCompletion）が存在する状態になる
            started = true;
            return true;
        } finally {
            if (!started) {
                // 録画スレッドを起動できなかったときの対応を消す。予約を押さえている間に
                // 消すので、予約を押さえている別の録画の対応を消すことは無い
                trackedRecordingIds.remove(videoId);
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
     * 巡回がこの動画をまだ配信中と確かめたことを、録り直しを待っている録画スレッドへ知らせる。
     * 待っているスレッドが無ければ何もしない。
     *
     * <p>録画済みの配信では巡回のたびにここへ来るので、マップを 1 回引くだけの軽い処理にしてある。
     * 録り直すかの判断に巡回の結果を使う理由は {@link #awaitCompletion} を参照。
     *
     * @param videoId 巡回が配信中と確かめた動画 ID
     */
    public void confirmStillLive(String videoId) {
        CompletableFuture<Void> confirmation = liveConfirmations.get(videoId);
        if (confirmation != null) {
            confirmation.complete(null);
        }
    }

    /**
     * 管理画面の操作で、録画中の録画を止める。止めた録画は「今の時点から」録り直さず、
     * そこまでを再生できる形にして {@code PARTIAL}（再生できるものが無ければ {@code FAILED}）で残す。
     *
     * <p><b>録画履歴は消さない。</b>止める手段がチャンネルの削除しか無かったときは、録画履歴・通知履歴まで
     * 連鎖削除で消えた。24 時間配信や、誤ってフィルターに掛かった配信を、そこまでの録画を残したまま止めるための操作。
     *
     * <p><b>止める yt-dlp は OS から探す</b>（{@link ProcessLauncher#findYtDlpProcessesWithCommandLineContaining(String)}）。
     * 再起動で追跡を失った録画も止めるため（{@link MonitoredChannelService#remove(Long)} と同じ）。
     * 探す文字列は動画 ID ではなく出力先（{@code <チャンネルID>/<動画ID>.%(ext)s}）にする。動画 ID だけだと、
     * 利用者が同じ動画を端末保存している yt-dlp（{@link DeviceDownloadService}。出力先は一時フォルダ）まで止めてしまうため。
     *
     * <p>止める処理は、SIGKILL へ切り替えるまで最大 30 秒待つため、仮想スレッドで行い HTTP の応答を待たせない。
     * 追跡中の録画は、プロセスが終わると {@code awaitCompletion} が救済・記録する。録り直しの前に巡回の合図を
     * 待っている録画（止める yt-dlp が無い）は、待ちを解いてすぐに記録させる。解かないと、合図の待ち（既定で最大 6 分）が
     * 切れるまで録画中のまま残り、{@link StopOutcome#STOPPING} の「最大 30 秒」を守れないため。追跡していない録画は
     * {@link RecordingReconciler} が次の後始末で記録する。{@code lastRecordedVideoId} は録画の開始時に更新済みなので、
     * 次の巡回で同じ配信を録り始めることもない。
     *
     * @param recordingId 録画履歴の主キー
     * @return 受け付けた結果
     * @throws com.example.monitor.exception.RecordingNotFoundException 指定 ID の録画履歴が存在しない場合（404）
     */
    public StopOutcome stopRecording(Long recordingId) {
        Recording recording = recordingHistoryService.findById(recordingId);
        if (recording.getStatus() != Recording.RecordingStatus.RECORDING) {
            log.warn("録画中ではないため止めません: recording={}, status={}", recordingId, recording.getStatus());
            return StopOutcome.NOT_RECORDING;
        }
        String videoId = recording.getVideoId();
        boolean tracked = isRecording(videoId);
        if (tracked) {
            // プロセスを止める前に付ける（stopRequested の JavaDoc 参照）
            stopRequested.add(videoId);
            // 巡回の合図を待っている録画には止める yt-dlp が無い。待ちを解き、起きたスレッドに印を見て録り直さずに記録させる
            CompletableFuture<Void> waiting = liveConfirmations.get(videoId);
            if (waiting != null) {
                waiting.complete(null);
            }
        }
        String outputFragment = FileNameUtils.stripExtension(recording.getFilePath(), ".mp4") + ".%(ext)s";
        List<ProcessHandle> handles = processLauncher.findYtDlpProcessesWithCommandLineContaining(outputFragment);
        if (handles.isEmpty() && !tracked) {
            log.warn("止める録画プロセスが見つかりませんでした: recording={}, video={}", recordingId, videoId);
            return StopOutcome.NO_PROCESS;
        }
        log.info("管理画面の操作で録画を止めます: recording={}, video={}, 操作者={}, pid={}",
                recordingId, videoId, RequestContext.currentUsername(),
                handles.stream().map(ProcessHandle::pid).toList());
        Thread.ofVirtual().name("stop-recording-" + videoId).start(() -> terminateForStop(videoId, handles));
        return StopOutcome.STOPPING;
    }

    /**
     * {@link #stopRecording(Long)} が探した yt-dlp を子孫ごと止める。
     *
     * @param videoId 止める録画の動画 ID（ログ用）
     * @param handles 止める yt-dlp
     */
    private void terminateForStop(String videoId, List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            if (ProcessTermination.terminateTreeAndAwait(handle, Duration.ofSeconds(30))) {
                // アプリの停止などで割り込まれた。残りには SIGKILL を送り済み
                return;
            }
            log.info("管理画面の操作で録画プロセスを止めました: video={}, pid={}", videoId, handle.pid());
        }
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
        command.addAll(YtDlpJsRuntime.options(jsRuntime));
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
     * 録画プロセスの終了を待って結果を履歴に記録する。再生できるファイルが何も残らなかったら、
     * {@code fallbackCommand} で「今の時点から」録り直す。1 回目の直後にその場で 1 回、それでも残らなければ
     * 巡回が「まだ配信中」と確かめるたびに（{@link #confirmStillLive(String)}）最大 {@link #MAX_LIVE_RETRIES} 回。
     *
     * <p><b>録り直すかは失敗の文言ではなく「完成ファイルが無い」ことで決める。</b>
     * yt-dlp のエラー文言は版ごとに変わりうるため。Twitch でアーカイブがサブスク限定のチャンネルは
     * {@code --live-from-start} だと必ず失敗するが、配信そのものは録れる（{@link #buildCommand} 参照）。
     * YouTube でも同じ動きになるが、1 回目で失敗するのはまれで、もう 1 回試しても害は無い。
     *
     * <p><b>その場の録り直しの後は、巡回の合図を待ってから録り直す。</b>配信開始の直後は、YouTube の
     * 一時的な拒否（bot の確認・429）や回線断で、1 回目もその場の録り直しも数秒で失敗することがある。
     * 巡回は録画を始めた時点で {@code lastRecordedVideoId} を更新するので、ここで諦めると配信が続いていても
     * 二度と録られない。一方、配信が終わっていれば「今の時点から」は失敗するか、アーカイブ全体を落とし始める。
     * そのため、配信中かを知っている巡回に確かめてもらう。待つ長さは巡回の間隔の
     * {@link #LIVE_CONFIRMATION_WAIT_CYCLES} 倍。待っている間も、画面には録画中と出る。
     *
     * <p><b>{@code lastRecordedVideoId} を戻して巡回に録り直させる形にしていない。</b>巡回が録画を始め直すと
     * {@link RecordingHistoryService#recordStart} が 2 行目の録画履歴を作り、1 行目の {@code FAILED} と同じファイル
     * （{@code {チャンネル}/{動画ID}.mp4}）を指す。2 行目が録れた後、{@link RecordingReconciler} の {@code FAILED} の
     * 救済が 1 行目も「ファイルのある失敗録画」として直すので、同じ録画が一覧に 2 つ並ぶ。このスレッドの中で
     * 録り直せば、行は 1 つのまま、予約（{@link ActiveVideoJobs}）も {@code RECORDING} も持ち続けられ、
     * 後始末・チャンネルの削除・録画の削除の今の扱いがそのまま効く。
     *
     * <p>録り直しの間も録画履歴は {@code RECORDING} のまま、動画IDの予約も保持し続ける。
     * 途中で {@code FAILED} にしたり予約を外したりすると、録り直し中の配信を
     * 次の巡回が二重に録画したり、{@link RecordingReconciler} が置き去りと誤判定したりするため。
     * 待機が中断された（アプリ停止など）場合は録り直さない。
     * 管理画面から止めた録画（{@link #stopRecording(Long)}）も録り直さない。
     * 空きが足りずに詰め替えを見送ったとき（{@link SalvageStatus#INSUFFICIENT_SPACE}）も録り直さない
     * （データは残っており、空きができた後の後始末で直せる。録り直すとさらに書き込む）。
     *
     * <p><b>待機のたびに、プロセスが終わっていれば結合の書きかけ（{@code .temp.mp4}）を消してから詰め替える。</b>
     * 残すと録画と同じくらいの大きさの空きを塞ぎ、詰め替えも空きが足りずに始められない（{@link #deleteMergeLeftover} 参照）。
     *
     * <p><b>プロセスが終わった時点で録画履歴の行が無ければ、録り直しも記録もしない。</b>行が無いのは、
     * チャンネルの削除で連鎖削除され、録画プロセスも止められたとき（{@link MonitoredChannelService#remove(Long)}）。
     * 止めた yt-dlp は完成ファイルを残さないことが多く、そのままでは「最初からの録画に失敗した」と見て録り直してしまい、
     * 削除したチャンネルを録り続ける。記録しても更新する行が無い。
     * 行があるかは、プロセスが終わった直後に加えて、録り直しを起動する直前（合図を待った後。合図が来なかったときも）にも
     * 確かめる。詰め替え（最大 600 秒）や合図の待ちの間に削除されると、削除時の停止処理はまだ起動していない録り直しを
     * 止められず、合図の来ないまま待ちが切れたときには消えた行へ失敗を記録してしまうため。
     *
     * <p><b>途中の例外（DB の失敗など）は捕まえてログに残す。</b>捕まえないと仮想スレッドの既定の処理で
     * 標準エラーに出るだけで、チャンネル別ログにも {@code /logs} 画面にも残らない。記録できずに
     * {@code RECORDING} のまま残った行は、予約を外した後に {@link RecordingReconciler} が補正する。
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
            if (exit.exitCode() != null) {
                // プロセスが終わった後に残る .temp.mp4 は結合の書きかけ。詰め替えの前に空きを返す
                deleteMergeLeftover(outputFile.getParent(), videoId);
            }
            if (!recordingHistoryService.exists(recordingId)) {
                log.info("録画履歴が削除されているため（チャンネルの削除）、録画の結果を記録しません: channel={}, video={}, exitCode={}",
                        channel.getChannelName(), videoId, exit.exitCode());
                return;
            }
            // プロセスは既に終了しているので、書き込み中のファイルを壊す心配なく詰め替えられる
            SalvageOutcome salvage = recordingSalvager.ensurePlayable(outputFile);
            boolean resumedMidway = false;

            // こちらから止めたとき（固まった・空き容量の下限を割った）は録り直さない。配信中でも同じ止まり方を繰り返しうる。
            // 空きが足りずに詰め替えを見送ったときも録り直さない（データは残っており、録り直すとさらに書き込む）
            // 管理画面から止めたときも録り直さない（録り直すと、止めたはずの配信を録り続ける）
            // retry == 0 は 1 回目の直後にその場で行う録り直し（従来どおり）。1 以降は、配信が終わっていれば
            // 「今の時点から」は失敗するか、終わった配信のアーカイブ全体を落とし始めるので、
            // 巡回が「まだ配信中」と確かめてから録り直す
            for (int retry = 0; retry <= MAX_LIVE_RETRIES; retry++) {
                if (salvage.isPlayable() || salvage.status() == SalvageStatus.INSUFFICIENT_SPACE
                        || fallbackCommand == null || exit.stoppedByUs() || stopRequested.contains(videoId)
                        || Thread.currentThread().isInterrupted()) {
                    break;
                }
                boolean stillLive = true;
                if (retry == 0) {
                    log.warn("最初からの録画に失敗したため、今の時点から録画し直します: channel={}, video={}, exitCode={}",
                            channel.getChannelName(), videoId, exit.exitCode());
                } else {
                    log.info("録画に失敗しました。巡回で配信が続いていると確かめられたら、今の時点から録画し直します: "
                                    + "channel={}, video={}, exitCode={}, 残り={}回",
                            channel.getChannelName(), videoId, exit.exitCode(), MAX_LIVE_RETRIES - retry + 1);
                    stillLive = awaitLiveConfirmation(videoId);
                }

                // 1 回目の終了から詰め替え（最大 600 秒）や合図の待ちを挟むので、その間の削除をここで確かめ直す。
                // チャンネル削除の停止処理はその時点で動いている yt-dlp しか探せないため、ここで止めないと録り続ける。
                // 合図が来なかったときもここを通す（削除したチャンネルは巡回されず合図が来ないので、消えた行へ失敗を記録しない）
                if (!recordingHistoryService.exists(recordingId)) {
                    log.info("録画履歴が削除されているため（チャンネルの削除）、録り直しも記録もせずに終えます: channel={}, video={}",
                            channel.getChannelName(), videoId);
                    return;
                }
                // 合図を待つ間に管理画面から止められた録画は録り直さない（stopRecording が待ちを解いてここへ来る。
                // 待ちが解けたのが巡回の合図でも同じ。stillLive より先に見るのはそのため）
                if (stopRequested.contains(videoId)) {
                    log.info("管理画面の操作で止められたため、録り直しをやめます: channel={}, video={}",
                            channel.getChannelName(), videoId);
                    break;
                }
                if (!stillLive) {
                    log.info("配信が続いていることを巡回で確かめられなかったため、録り直しをやめます: channel={}, video={}",
                            channel.getChannelName(), videoId);
                    break;
                }
                if (retry > 0) {
                    log.warn("配信が続いているため、今の時点から録画し直します（{}/{}回目）: channel={}, video={}",
                            retry, MAX_LIVE_RETRIES, channel.getChannelName(), videoId);
                }
                try {
                    Process relaunched = processLauncher.launch(fallbackCommand, YtDlpLogFile.of(videoId));
                    resumedMidway = true;
                    exit = awaitExit(relaunched, channel, videoId, outputFile.getParent());
                } catch (IOException e) {
                    log.error("録り直しの録画プロセスの起動に失敗しました: channel={}, video={}",
                            channel.getChannelName(), videoId, e);
                    break;
                }
                if (exit.exitCode() != null) {
                    // プロセスが終わった後に残る .temp.mp4 は結合の書きかけ。詰め替えの前に空きを返す
                    deleteMergeLeftover(outputFile.getParent(), videoId);
                }
                if (!recordingHistoryService.exists(recordingId)) {
                    log.info("録画履歴が削除されているため（チャンネルの削除）、録画の結果を記録しません: channel={}, video={}, exitCode={}",
                            channel.getChannelName(), videoId, exit.exitCode());
                    return;
                }
                salvage = recordingSalvager.ensurePlayable(outputFile);
            }

            recordOutcome(recordingId, channel, videoId, salvage, resumedMidway, exit.exitCode());
        } catch (RuntimeException e) {
            // DB の確認・記録（exists・mark*）が失敗しても、仮想スレッドの既定の処理（標準エラー）に流さず
            // チャンネル別ログに残す（finally より前なので MDC がまだ効いている）。RECORDING のまま残った行は、
            // 予約を外した後に RecordingReconciler が完成ファイルの有無で補正する
            log.error("録画の結果を記録できませんでした。録画履歴は後始末（RecordingReconciler）が補正します: channel={}, video={}",
                    channel.getChannelName(), videoId, e);
        } finally {
            // 結果を記録し終えてから追跡を外す。順序を逆にすると、その隙に
            // RecordingReconciler が「追跡されていないのに RECORDING のまま＝置き去り」と
            // 誤判定してしまう
            stopRequested.remove(videoId);
            activeVideoJobs.release(videoId);
            // 予約を外した後に消す。先に消すと、予約が外れる前に来た startRecording が
            // 対応を読めず、削除したチャンネルの録画が押さえていたのを「既に録画中」と見て
            // true を返す。値も比べて消すのは、予約を外した直後に始まった次の録画の
            // 対応を消さないため
            trackedRecordingIds.remove(videoId, recordingId);
            if (mdcChannelId != null) {
                MDC.remove(MDC_CHANNEL_ID_KEY);
            }
        }
    }

    /**
     * 巡回が {@link #confirmStillLive(String)} でこの動画をまだ配信中と知らせてくるのを待つ。
     *
     * @param videoId 録画対象の動画 ID
     * @return 待つ間に知らせが来たら {@code true}。来なかった・割り込まれた場合は {@code false}（割り込み状態は立て直す）
     */
    private boolean awaitLiveConfirmation(String videoId) {
        CompletableFuture<Void> confirmation = new CompletableFuture<>();
        liveConfirmations.put(videoId, confirmation);
        try {
            confirmation.get((long) monitorProperties.youtube().intervalSeconds() * LIVE_CONFIRMATION_WAIT_CYCLES,
                    TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            liveConfirmations.remove(videoId, confirmation);
        }
    }

    /**
     * 録画プロセスの終了を待つ。出力が {@link #stallMinutes} 分止まっていたら、または空き容量が下限を割ったら、子孫ごと止める。
     *
     * <p>出力はファイルへ向けているため（クラスの JavaDoc「プロセスの生存期間」参照）、ここでは読まない。
     * 1 分ごとに {@link RecordingActivity#lastModified(Path, String)} を見る。待機を始めた時刻を下限にするのは、
     * 起動直後でまだ何も書かれていないときと、録り直しで待ち直したときに、古い時刻で誤って止めないため。
     * 更新時刻を読めなかったときは止めない（「判定できなかった」を「固まった」と扱わない）。
     *
     * <p><b>空き容量（{@link #usableBytesIfBelowReserve}）は固まりの判定より先に見る。</b>固まりの判定は
     * 更新時刻を読めないときや止まっていないときに {@code continue} で次の 1 分へ飛ぶので、後に置くと
     * そのたびに空き容量を見なくなる。止める理由はクラスの JavaDoc を参照。
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
                Long usableBytes = usableBytesIfBelowReserve(outputDirectory);
                if (usableBytes != null) {
                    long reserveBytes = DiskSpaceUtils.reserveBytes(minFreeGb);
                    log.warn("空き容量が下限を下回ったため、録画プロセスを止めます: channel={}, video={}, 空き={}MB, 下限={}MB",
                            channel.getChannelName(), videoId, usableBytes / (1024L * 1024), reserveBytes / (1024L * 1024));
                    if (ProcessTermination.terminateTreeAndAwait(process.toHandle(), Duration.ofSeconds(30))) {
                        Thread.currentThread().interrupt();
                        return new ExitResult(null, true);
                    }
                    try {
                        discordNotifier.sendAdminAlert("空き容量が " + usableBytes / (1024L * 1024 * 1024) + "GB（下限 "
                                + reserveBytes / (1024L * 1024 * 1024) + "GB）を下回ったため、録画を止めました: "
                                + channel.getChannelName() + "（" + videoId + "）。止めるまでの分は、空きができた後の後始末で"
                                + "再生できる形に直します（詰め替えには録画と同じ大きさの空きが要ります）。");
                    } catch (RuntimeException e) {
                        // 通知の失敗で録画の記録を妨げない
                        log.warn("空き容量で録画プロセスを止めたことを管理者へ通知できませんでした", e);
                    }
                    return new ExitResult(process.waitFor(), true);
                }
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
     * 空き容量が録画中の下限（{@link DiskSpaceUtils#reserveBytes(long)}）を割っていれば、そのときの空きを返す。
     *
     * <p>空き容量を読めなかったときは割っていないと扱う（「判定できなかった」を「満杯」と扱わない。
     * {@link #startRecording} と同じ）。
     *
     * @param outputDirectory 録画フォルダ
     * @return 下限を割っていれば空き容量（バイト）。割っていない・確認しない（{@link #minFreeGb} が 0）・読めなかった場合は {@code null}
     */
    private Long usableBytesIfBelowReserve(Path outputDirectory) {
        long reserveBytes = DiskSpaceUtils.reserveBytes(minFreeGb);
        if (reserveBytes <= 0) {
            return null;
        }
        DiskSpaceUtils.Capacity disk = DiskSpaceUtils.read(outputDirectory);
        if (disk.error() != null || disk.usableBytes() == null || disk.usableBytes() >= reserveBytes) {
            return null;
        }
        return disk.usableBytes();
    }

    /**
     * yt-dlp が結合・修正の途中で書いていた {@code {動画ID}.temp.mp4} を消す。
     *
     * <p>yt-dlp は結合（Twitch では修正）に失敗すると、この書きかけ（再生できない）を消さずに終わる。
     * 空き容量が足りずに失敗した場合は空きを 0 まで使い切った大きさで残り、空きを塞ぎ続けて H2 の書き込みも
     * 救済（{@link RecordingSalvager}）も止めたままにする。こちらから止めた場合も同じく残る。
     * yt-dlp は結合が成功すると {@code .temp.mp4} を {@code .mp4} へ置き換え、結合の ffmpeg の終わりを待ってから
     * 終わるので、プロセスが終わった後に残っているものは必ず書きかけ。
     * <b>プロセスが終わったことを確かめた後（{@code ExitResult.exitCode()} が {@code null} でないとき）にだけ呼ぶ。</b>
     *
     * @param outputDirectory 録画フォルダ
     * @param videoId         録画対象の動画 ID
     */
    private void deleteMergeLeftover(Path outputDirectory, String videoId) {
        Path leftover = outputDirectory.resolve(videoId + ".temp.mp4");
        try {
            if (Files.deleteIfExists(leftover)) {
                log.warn("yt-dlp の結合途中のファイルが残っていたため消しました（結合に失敗したか、途中で止めたため）: video={}, file={}",
                        videoId, leftover);
            }
        } catch (IOException e) {
            log.warn("結合途中のファイルを消せませんでした: video={}, file={}", videoId, leftover, e);
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
            if (salvage.status() == SalvageStatus.INSUFFICIENT_SPACE) {
                log.warn("空き容量が足りず再生できる形にできなかったため、いったん失敗として記録します。"
                                + "空きができた後の後始末で直します: channel={}, video={}, exitCode={}",
                        channel.getChannelName(), videoId, exitCode);
            } else {
                log.warn("再生できる録画ファイルを用意できなかったため失敗として記録します: "
                                + "channel={}, video={}, exitCode={}",
                        channel.getChannelName(), videoId, exitCode);
            }
            recordingHistoryService.markFailed(recordingId);
            alertRecordingFailed(channel, videoId, exitCode);
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

    /**
     * 録画を始められないことを管理者へ知らせる。直るまでは 2 回目以降を送らない（{@link #startFailureAlerted} 参照）。
     *
     * @param message 知らせる本文
     */
    private void alertStartFailureOnce(String message) {
        if (!startFailureAlerted.compareAndSet(false, true)) {
            return;
        }
        try {
            discordNotifier.sendAdminAlert(message);
        } catch (RuntimeException e) {
            // 通知の失敗で録画の判断（false を返す）を変えない
            log.warn("録画を始められないことを管理者へ通知できませんでした", e);
        }
    }

    /**
     * 録画に失敗した（再生できるファイルが残らなかった）ことを管理者へ知らせる。
     *
     * <p>失敗はダッシュボードの「直近の録画失敗」にも出るが、開かないと気付けない。yt-dlp は YouTube 側の変更で
     * 壊れやすく、壊れると以降の録画がすべて失敗する。気付くのが遅れるほど取り返せない配信が増えるため、その場で知らせる。
     *
     * <p>空き容量のような「1 回だけ」の抑制はしない。呼び出し元の巡回は録画を始めた時点で
     * {@code lastRecordedVideoId} を更新済みで同じ配信を録り始め直さず、録り直しは記録より前に
     * {@code awaitCompletion} の中で済ませるため、通知は失敗した配信 1 本につき 1 回で済む。
     * 固まった・空き容量が下限を割ったために止めた録画が失敗に終わったときは、止めたことの通知（{@link #awaitExit}）に
     * 続けてこの通知も届く。止めた結果どうなったかが分かるので、1 通にまとめない。
     *
     * <p>空きが足りずに詰め替えを見送ったとき（{@link SalvageStatus#INSUFFICIENT_SPACE}）も送る。データは残っているが、
     * 空きを作らない限り後始末でも直らないため。配信の終わった後の結合が空きを使い切って失敗したときは、
     * 止めたことの通知が無いので、この通知が空きを作るきっかけになる。
     *
     * <p>yt-dlp の出力の末尾を添えるのは、失敗の理由を Discord だけで読めるようにするため。全文は
     * {@link YtDlpLogFile#of(String)} のファイルにある。
     *
     * @param channel  録画対象のチャンネル
     * @param videoId  録画対象の動画 ID
     * @param exitCode {@code yt-dlp} の終了コード。取得できなかった場合は {@code null}
     */
    private void alertRecordingFailed(MonitoredChannel channel, String videoId, Integer exitCode) {
        StringBuilder message = new StringBuilder()
                .append("録画に失敗しました（再生できるファイルが残りませんでした）: ")
                .append(channel.getChannelName()).append("（").append(videoId).append("）、終了コード ")
                .append(exitCode == null ? "不明" : exitCode)
                .append("。yt-dlp の出力: ").append(YtDlpLogFile.of(videoId));
        String tail = YtDlpLogFile.tail(videoId, FAILURE_ALERT_LOG_TAIL_BYTES);
        if (!tail.isEmpty()) {
            message.append("\n```\n").append(tail).append("\n```");
        }
        try {
            discordNotifier.sendAdminAlert(message.toString());
        } catch (RuntimeException e) {
            // 通知の失敗で録画の記録を妨げない（失敗の記録は済んでいる）
            log.warn("録画の失敗を管理者へ通知できませんでした: video={}", videoId, e);
        }
    }
}
