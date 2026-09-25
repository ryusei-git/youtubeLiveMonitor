package com.example.monitor.scheduler;

import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.NotificationOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.Platform;
import com.example.monitor.platform.StreamPlatform;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.service.NotificationDispatcher;
import com.example.monitor.service.NotificationHistoryService;
import com.example.monitor.service.RecordingIntentResolver;
import com.example.monitor.service.RecordingIntentResolver.RecordingIntent;
import com.example.monitor.service.StreamRecorder;
import com.example.monitor.service.UserNotificationService;
import com.example.monitor.util.ChannelLogContext;
import com.example.monitor.util.DatabaseUpdateVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 監視対象チャンネルを定期的に巡回し、新しい配信を見つけたら通知・録画する。このアプリの中核。
 *
 * <h2>1 サイクルの流れ</h2>
 * <ol>
 *   <li>登録済みチャンネルを DB から読み出し、<b>プラットフォームごとに束ねる</b></li>
 *   <li>束ねた単位で {@link StreamPlatform#detectLiveStreams} を 1 回だけ呼び、配信中かどうか・
 *       タイトルを調べる。まとめて問い合わせられるプラットフォーム（Twitch は 1 リクエストで
 *       100 チャンネル）ではここが 1 回の通信になり、そうでないプラットフォーム（YouTube）では
 *       既定実装が 1 件ずつ問い合わせる。YouTube はクォータを消費しない HTML 解析で判定する。
 *       <b>判定できなかった場合は配信状態に触れず、連続失敗回数だけを増やす</b></li>
 *   <li>YouTube の待機所（配信開始前の予約枠）を検知した場合は、開始予定時刻などを
 *       {@link MonitoredChannel#upcomingVideoId} へ記録する。配信中／配信していないに
 *       転じたら消す</li>
 *   <li>配信中であれば、録画が有効かつタイトルフィルター（{@link MonitoredChannel#matchesFilter}）
 *       に一致するチャンネルは {@link StreamRecorder} で録画を開始する
 *       （通知の成否とは無関係、こちらも動画IDが変わるたびに1回だけ）</li>
 *   <li>配信中であれば、購読していて Webhook を登録している利用者へ、まだ送っていなければ
 *       配信開始を送る（{@link UserNotificationService}。以降の全体向けの判定とは独立）</li>
 *   <li>配信中で、かつ前回通知した動画と異なれば「新しい配信」と判断する</li>
 *   <li><b>タイトルフィルターに一致しない配信はここで打ち切る</b>（通知しない）。
 *       詳細取得より前に判定するので、対象外の配信でクォータを消費しない</li>
 *   <li>{@link StreamPlatform#fetchDetails} で通知に必要な詳細情報を取得する
 *       （YouTube はここでクォータを 1 消費する）</li>
 *   <li>{@link NotificationDispatcher} で通知し、結果を履歴に残す</li>
 *   <li>通知に成功した場合のみ「通知済みの動画 ID」を更新する</li>
 * </ol>
 *
 * <p>置き去りになった録画履歴の補正は巡回に含めない。{@code ffmpeg} を待つ間に配信検知が
 * 止まるため、{@link com.example.monitor.service.RecordingReconciler} が別のスレッドで行う。
 *
 * <h2>失敗しても止まらない設計</h2>
 * 1 チャンネルの処理で例外が出ても捕まえて次のチャンネルへ進む。
 * また通知・録画開始のどちらも失敗したときは対応する「済み」の動画 ID を更新しないため、
 * 次のサイクルで同じ配信が再び「新しい配信」と判定され、自動的に再試行される。
 * 専用のリトライ処理を書かずに済ませるための工夫。
 *
 * <p>ただし通知の再試行には上限（{@link #MAX_NOTIFICATION_ATTEMPTS}）を設けている。
 * Webhook URL の設定ミスのような「待っても直らない失敗」では、上限が無いと配信が続く限り
 * 毎サイクル試行し続け、失敗履歴が際限なく増えてクォータも消費し続けるため。
 * 回数が 0 に戻るのは<b>配信が終わったとき（NOT_LIVE を検知）だけではない</b>。
 * <b>前回と別の配信を検知したときにも戻す。</b>巡回間隔内での枠の差し替えや、アプリ停止中の
 * 切り替えでは NOT_LIVE を一度も挟まずに次の配信へ移ることがあり、そこを通ると前の配信の
 * 失敗回数がそのまま適用されて、新しい配信への通知が一度も試されないまま終わるため。
 *
 * <h2>実行間隔</h2>
 * {@code fixedDelay} を使っているため、前回の処理が終わってから次の待ち時間が始まる。
 * 処理が長引いても多重に走ることはない。間隔は環境変数 {@code MONITOR_INTERVAL_SECONDS} で変更できる。
 * 次の実行を待たずに確かめたい場合は {@link #pollNow()}（画面の「今すぐチェック」）を使う。
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
@Slf4j
public class LiveStreamPollingScheduler {

    /**
     * 1 つの配信に対して通知を試みる上限回数。
     *
     * <p>通知に失敗したとき「通知済み」にしないことで次サイクルの再送信になる仕組みだが、
     * 上限が無いと <b>Webhook の設定ミスのような直らない失敗で、配信が続く限り毎サイクル
     * 試行し続ける</b>（失敗履歴が際限なく増え、詳細取得のクォータも消費し続ける）。
     * 一時的な通信不良なら数回のうちに復帰するはずなので、そこを救える程度の回数にしている。
     */
    private static final int MAX_NOTIFICATION_ATTEMPTS = 3;

    private final MonitoredChannelRepository monitoredChannelRepository;
    private final StreamPlatformRegistry streamPlatformRegistry;
    private final NotificationDispatcher notificationDispatcher;
    private final NotificationHistoryService notificationHistoryService;
    private final StreamRecorder streamRecorder;
    private final RecordingIntentResolver recordingIntentResolver;
    private final com.example.monitor.service.OnlineVideoService onlineVideoService;
    private final UserNotificationService userNotificationService;

    /**
     * 巡回が実行中かどうか。定期実行と手動実行が同時に走るのを防ぐために使う。
     *
     * <p>{@code fixedDelay} は定期実行同士の重複は防ぐが、手動実行（{@link #pollNow()}）とは
     * 独立して動くため、この印がないと同じ配信に対して録画プロセスを二重に起動しうる
     * （{@link StreamRecorder#startRecording} の「録画中か」の確認と起動の間に割り込む余地がある）。
     */
    private final AtomicBoolean pollingInProgress = new AtomicBoolean(false);

    /**
     * 監視を行うか。確認用の起動（{@code bin/preview.sh}）で {@code false} にし、本番と並べて
     * 動かしても録画・通知が二重に起きないようにするため。初期値を {@code true} にしているのは、
     * Spring を通さずに組み立てるテストでも今までどおり巡回させるため。
     */
    @Value("${monitor.scheduling.enabled:true}")
    private boolean schedulingEnabled = true;

    /**
     * 登録済みの全チャンネルを 1 巡する。設定された間隔で繰り返し呼ばれる。
     */
    @Scheduled(fixedDelayString = "${monitor.youtube.interval-seconds:120}", timeUnit = TimeUnit.SECONDS)
    public void pollAllChannels() {
        runPollingCycle();
    }

    /**
     * 次の定期実行を待たずに、その場で 1 巡する。画面の「今すぐチェック」から呼ばれる。
     *
     * <p>録画設定やタイトルフィルターを変更した直後に、その設定が意図通り効くかを
     * 確かめる用途を想定している（変更のたびに巡回間隔ぶん待つのを避けるため）。
     *
     * @return 巡回を実行した場合 {@code true}。既に巡回中で今回の実行を見送った場合は {@code false}
     */
    public boolean pollNow() {
        return runPollingCycle();
    }

    /**
     * 全チャンネルを 1 巡する本体。定期実行と手動実行の共通処理。
     *
     * <p><b>チャンネルはプラットフォームごとにまとめてから調べる。</b>
     * {@link StreamPlatform#detectLiveStreams} は、まとめて問い合わせられるプラットフォームでは
     * 1 リクエストに集約される（Twitch は 100 チャンネルまで）。1 件ずつ呼ぶとこの最適化が
     * 一切効かず、チャンネル数に比例して通信回数が増える。
     *
     * @return 巡回を実行した場合 {@code true}。既に巡回中で見送った場合は {@code false}
     */
    private boolean runPollingCycle() {
        if (!schedulingEnabled) {
            log.debug("定期処理が無効（monitor.scheduling.enabled=false）のため、監視サイクルを行いません");
            return false;
        }
        if (!pollingInProgress.compareAndSet(false, true)) {
            log.info("既に監視サイクルが実行中のため、今回の実行は見送ります");
            return false;
        }

        try {
            List<MonitoredChannel> channels = monitoredChannelRepository.findAll();
            log.debug("監視サイクルを開始します: 対象={}件", channels.size());

            // 登録順を保ったままプラットフォームごとに束ねる（ログの並びが巡回のたびに変わらないように）
            Map<Platform, List<MonitoredChannel>> channelsByPlatform = channels.stream()
                    .collect(Collectors.groupingBy(
                            MonitoredChannel::getPlatform, LinkedHashMap::new, Collectors.toList()));

            channelsByPlatform.forEach(this::pollPlatformGroup);
            return true;
        } finally {
            pollingInProgress.set(false);
        }
    }

    /**
     * 同じプラットフォームのチャンネルをまとめて調べ、1 件ずつ後処理する。
     *
     * <p>後処理の間は MDC へチャンネル ID を設定する（{@link ChannelLogContext}）。これにより
     * その処理中に出力されたログが（下位のクラスが出したものも含めて）
     * {@code logs/channels/{チャンネルID}.log} に自動的に振り分けられる。
     * 検知そのもののログは {@link StreamPlatform#detectLiveStreams} 側で同じ仕組みに載せている。
     *
     * @param platform 対象のプラットフォーム
     * @param channels そのプラットフォームに属するチャンネル
     */
    private void pollPlatformGroup(Platform platform, List<MonitoredChannel> channels) {
        StreamPlatform streamPlatform = streamPlatformRegistry.get(platform);
        Map<String, LiveStreamDetection> detections = detectAll(platform, streamPlatform, channels);

        for (MonitoredChannel channel : channels) {
            ChannelLogContext.runWithChannel(channel.getYoutubeChannelId(), () -> {
                try {
                    // 応答に含まれなかったチャンネルを「配信していない」に倒してはならない。
                    // 調べられなかったものを平常運転として記録することになる
                    LiveStreamDetection detection = detections.getOrDefault(
                            channel.getYoutubeChannelId(), LiveStreamDetection.failed());
                    try {
                        onlineVideoService.observe(channel, detection);
                    } catch (RuntimeException e) {
                        log.warn("視聴先の保存に失敗しました。通知・録画は継続します: channel={}", channel.getId(), e);
                    }
                    checkChannelAndNotify(streamPlatform, channel, detection);
                } catch (Exception e) {
                    // 1 チャンネルの異常で他チャンネルの監視まで止めない
                    log.error("チャンネルの監視中に想定外のエラーが発生しました: name={}, channel={}",
                            channel.getChannelName(), channel.getYoutubeChannelId(), e);
                }
            });
        }
    }

    /**
     * プラットフォームへまとめて問い合わせる。
     *
     * <p>問い合わせ自体が失敗した場合は空の結果を返す。呼び出し側は結果に含まれないチャンネルを
     * 「判定できなかった」として扱うため、<b>通信障害が「全チャンネルが配信していない」として
     * 記録されることはない</b>（このアプリで最も避けたい取り違え）。
     *
     * @param platform       対象のプラットフォーム（ログ用）
     * @param streamPlatform 問い合わせ先の実装
     * @param channels       そのプラットフォームに属するチャンネル
     * @return 識別子ごとの判定結果。問い合わせに失敗した場合は空
     */
    private Map<String, LiveStreamDetection> detectAll(
            Platform platform, StreamPlatform streamPlatform, List<MonitoredChannel> channels) {

        List<String> channelIds = channels.stream()
                .map(MonitoredChannel::getYoutubeChannelId)
                .toList();
        try {
            return streamPlatform.detectLiveStreams(channelIds);
        } catch (RuntimeException e) {
            log.error("配信状態の一括取得に失敗しました: platform={}, 対象={}件",
                    platform, channelIds.size(), e);
            return Map.of();
        }
    }

    /**
     * 1 チャンネルの検知結果を受けて、新しい配信が始まっていれば通知・録画する。
     *
     * <p>検知そのものは呼び出し側がまとめて済ませている。このメソッドは<b>検知結果を受け取る</b>形に
     * しておくことで、まとめて問い合わせるプラットフォームと 1 件ずつ問い合わせるプラットフォームの
     * どちらでも同じ後処理を通せる。
     *
     * <p><b>DB の更新に {@code save(entity)} を使ってはならない。</b>
     * このメソッドが扱っているエンティティは巡回開始時に読み込んだもので、
     * その後に管理用 API 経由でチャンネル名などが変更されている可能性がある。
     * {@code save} は全カラムを書き戻すため、その変更を古い値で消してしまう
     * （実際に発生した不具合）。更新には対象カラムを限定した専用メソッドを使う。
     *
     * @param platform  このチャンネルを担当するプラットフォーム実装（詳細取得に使う）
     * @param channel   調査対象のチャンネル
     * @param detection そのチャンネルの検知結果
     */
    private void checkChannelAndNotify(
            StreamPlatform platform, MonitoredChannel channel, LiveStreamDetection detection) {

        if (detection.isDetectionFailed()) {
            // 配信中かどうかは分からないので配信状態には触れず、失敗が続いていることだけを記録する
            DatabaseUpdateVerifier.verify(
                    monitoredChannelRepository.recordDetectionFailure(channel.getId(), LocalDateTime.now()),
                    "配信状態の判定失敗の記録", channel.getId());
            return;
        }

        // updateObservedLiveState が currentLiveVideoId を上書きしてしまうため、その前に控える。
        // 「前の配信」と「今の配信」を見分ける材料はこれしかない
        String previousLiveVideoId = channel.getCurrentLiveVideoId();

        // 判定できた場合のみ観測結果を記録する（通知の成否とは無関係に毎回）
        DatabaseUpdateVerifier.verify(
                monitoredChannelRepository.updateObservedLiveState(
                        channel.getId(), detection.isLive(),
                        // UPCOMING も videoId を持つが、予約枠の ID を「配信中の動画」として残さない
                        detection.isLive() ? detection.videoId() : null, LocalDateTime.now()),
                "配信状態の記録", channel.getId());

        // 配信予定（待機所）の記録もここで更新する。UPCOMING なら上書き、
        // LIVE・NOT_LIVE なら消す（予定が現実になった／消えたのどちらか）。
        // DETECTION_FAILED はここまで来ないため触れずに済む
        if (detection.isUpcoming()) {
            DatabaseUpdateVerifier.verify(
                    monitoredChannelRepository.updateUpcoming(channel.getId(), detection.videoId(),
                            detection.title(), detection.scheduledStartTime()),
                    "配信予定の記録", channel.getId());
        } else {
            DatabaseUpdateVerifier.verify(
                    monitoredChannelRepository.clearUpcoming(channel.getId()),
                    "配信予定のクリア", channel.getId());
        }

        // アイコンは読めた回だけ、変わっていれば記録する。null で消さないのは、
        // 一時的に読み取れなかっただけで前の値を捨てないため
        if (detection.channelIconUrl() != null
                && !detection.channelIconUrl().equals(channel.getChannelIconUrl())) {
            DatabaseUpdateVerifier.verify(
                    monitoredChannelRepository.updateChannelIconUrl(channel.getId(), detection.channelIconUrl()),
                    "チャンネルアイコンの記録", channel.getId());
        }

        if (!detection.isLive()) {
            // 配信が終わったので、この配信に対する通知失敗の回数は次の配信に持ち越さない
            if (channel.getNotificationFailureCount() > 0) {
                DatabaseUpdateVerifier.verify(
                        monitoredChannelRepository.resetNotificationFailureCount(channel.getId()),
                        "通知失敗回数のリセット", channel.getId());
            }
            return;
        }

        String videoId = detection.videoId();

        // 失敗回数は「この配信に対して何回失敗したか」なので、配信が変われば数え直す。
        // NOT_LIVE を挟まずに次の配信へ切り替わる経路（巡回間隔内での枠の差し替え、
        // アプリ停止中の切り替え）があり、そこを通ると前の配信の失敗回数がそのまま適用され、
        // 新しい配信への通知が一度も試されないまま終わる（実際に起こりうる指摘）。
        //
        // ローカル変数に持つのは、DB を 0 に戻しても読み込み済みのエンティティは
        // 古い値のままで、このサイクルの上限判定が「上限到達」のままになるため。
        // エンティティ側を書き換えないのは、巡回ループが扱うエンティティを変更しないという
        // このクラスの方針（save(entity) を呼ばない理由と同じ）に合わせている。
        // 前の配信が「分からない」ときは戻さない。分からないものを「別の配信だ」と断定すると、
        // 上限を設けた意味（直らない失敗を試行し続けない）が消えるため
        // （「配信していない」と「判定できなかった」を区別するのと同じ考え方）。
        // 失敗回数が 1 以上なら、その配信を検知したときに currentLiveVideoId も
        // 記録されているはずなので、実運用でこの条件が効く場面は無い。
        int notificationFailureCount = channel.getNotificationFailureCount();
        if (notificationFailureCount > 0 && previousLiveVideoId != null
                && !Objects.equals(videoId, previousLiveVideoId)) {
            log.info("前の配信とは別の配信を検知したため、通知の失敗回数を数え直します: "
                            + "name={}, 前の配信={}, 今の配信={}, 失敗回数={}",
                    channel.getChannelName(), previousLiveVideoId, videoId, notificationFailureCount);
            DatabaseUpdateVerifier.verify(
                    monitoredChannelRepository.resetNotificationFailureCount(channel.getId()),
                    "通知失敗回数のリセット（別の配信を検知）", channel.getId());
            notificationFailureCount = 0;
        }

        // 録画は通知の成否と無関係に、動画IDが変わるたびに1回だけ試みる
        maybeStartRecording(channel, detection);

        Supplier<Optional<LiveStreamDetails>> details = fetchDetailsOnce(platform, channel, videoId);

        // 利用者ごとの通知は、全体向けの「通知済み」・失敗回数・フィルターとは切り離して判定する
        // （UserNotificationService 参照）。下の全体向けの打ち切り（return）より前に置くのはそのため。
        // 例外は投げないので、全体向けの通知は必ずこの後に続く
        userNotificationService.notifySubscribers(channel, videoId, details);

        if (Objects.equals(videoId, channel.getLastNotifiedVideoId())) {
            log.debug("配信中ですが通知済みのためスキップします: name={}, video={}",
                    channel.getChannelName(), videoId);
            return;
        }

        if (!shouldNotifyByTitle(channel, detection)) {
            return;
        }

        if (notificationFailureCount >= MAX_NOTIFICATION_ATTEMPTS) {
            log.debug("この配信への通知は上限まで失敗しているため再送信しません: name={}, video={}",
                    channel.getChannelName(), videoId);
            return;
        }

        log.info("新しい配信を検知しました: name={}, video={}", channel.getChannelName(), videoId);

        Optional<LiveStreamDetails> liveStream = details.get();
        if (liveStream.isEmpty()) {
            // 詳細が取れないと通知本文を作れない。これも失敗として数え、際限なく試行しないようにする
            log.warn("配信の詳細情報を取得できなかったため、今回の通知を見送ります: video={}", videoId);
            DatabaseUpdateVerifier.verify(
                    monitoredChannelRepository.incrementNotificationFailureCount(channel.getId()),
                    "通知失敗回数の加算（詳細取得の失敗）", channel.getId());
            return;
        }

        NotificationOutcome outcome = notificationDispatcher.notifyLiveStreamStarted(liveStream.get());
        notificationHistoryService.recordAttempt(channel, videoId, liveStream.get().getTitle(), outcome);

        if (outcome.successful()) {
            // 通知済みの記録と同時に失敗回数も 0 に戻る
            DatabaseUpdateVerifier.verify(
                    monitoredChannelRepository.updateLastNotifiedVideoId(channel.getId(), videoId),
                    "通知済み動画IDの更新", channel.getId());
            return;
        }

        // 失敗時は通知済みにしない → 次のサイクルで再送信される（上限回数まで）
        DatabaseUpdateVerifier.verify(
                monitoredChannelRepository.incrementNotificationFailureCount(channel.getId()),
                "通知失敗回数の加算（送信の失敗）", channel.getId());
        if (notificationFailureCount + 1 >= MAX_NOTIFICATION_ATTEMPTS) {
            log.error("通知に{}回失敗したため、この配信への再送信を諦めます: name={}, video={}",
                    MAX_NOTIFICATION_ATTEMPTS, channel.getChannelName(), videoId);
        }
    }

    /**
     * 配信の詳細を、最初に必要になったときに 1 回だけ取得する入れ物を作る。
     *
     * <p>詳細は利用者ごとの通知と全体向けの通知の両方が使う。YouTube では取得のたびにクォータを
     * 1 消費するため、同じ巡回で 2 回取らない。<b>必要になるまで取らない</b>のは、どちらも送らない巡回
     * （通知済み・フィルター対象外など）でクォータを使わないため（全体向けの「フィルターの判定は
     * 詳細取得より前」の順序もこれで保たれる）。
     *
     * @param platform このチャンネルを担当するプラットフォーム実装
     * @param channel  対象チャンネル
     * @param videoId  配信の動画 ID
     * @return 取り出すたびに同じ結果を返す入れ物
     */
    private static Supplier<Optional<LiveStreamDetails>> fetchDetailsOnce(
            StreamPlatform platform, MonitoredChannel channel, String videoId) {
        AtomicReference<Optional<LiveStreamDetails>> fetched = new AtomicReference<>();
        return () -> {
            if (fetched.get() == null) {
                fetched.set(platform.fetchDetails(channel.getYoutubeChannelId(), videoId));
            }
            return fetched.get();
        };
    }

    /**
     * この配信をタイトルフィルターの観点から通知してよいかを判定する。
     *
     * <p>「指定したタグがタイトルに無い配信は通知しない」という設定を実現する部分。
     * <b>通知済みの記録（{@code lastNotifiedVideoId}）は更新しない。</b>
     * 配信途中でタイトルにタグが足された場合、次の巡回で対象になって通知が飛ぶようにするため
     * （判定材料のタイトルは検知時の HTML から得ており追加のクォータを消費しないので、
     * 毎サイクル評価し直しても負荷は増えない）。
     *
     * @param channel   対象チャンネル
     * @param detection 検知結果（配信中であることが確定しているもの）
     * @return 通知してよければ {@code true}
     */
    private boolean shouldNotifyByTitle(MonitoredChannel channel, LiveStreamDetection detection) {
        if (channel.matchesFilter(detection.title(), detection.category())) {
            return true;
        }

        if (detection.title() == null) {
            // タイトルが取れないのは通常あり得ない（配信検知と同じ応答から読んでいるため）。
            // YouTube 側の構造変更でタイトル抽出だけが壊れると、フィルター設定済みの
            // チャンネルは黙って通知されなくなる。気づけるよう警告として残す
            log.warn("配信タイトルを取得できずフィルターを判定できないため、通知を見送ります: "
                            + "name={}, video={}, フィルター={}",
                    channel.getChannelName(), detection.videoId(), channel.getRecordTitleKeywords());
        } else {
            log.info("フィルターに一致しないため通知しません: name={}, title={}, カテゴリ={}, フィルター={}",
                    channel.getChannelName(), detection.title(),
                    detection.category() == null ? "-" : detection.category(),
                    channel.getRecordTitleKeywords());
        }
        return false;
    }

    /**
     * 録画が有効なチャンネルで、タイトルフィルターにも一致し、まだこの配信を録画していなければ
     * 録画を開始する。
     *
     * @param channel   対象チャンネル
     * @param detection 検知結果（配信中であることが確定しているもの）
     */
    private void maybeStartRecording(MonitoredChannel channel, LiveStreamDetection detection) {
        String videoId = detection.videoId();
        String title = detection.title();

        // 希望はチャンネル単位の設定と購読ごとの設定の論理和で決まる
        // （誰か1人でも希望していれば録画する。RecordingIntentResolver 参照）
        RecordingIntent intent = recordingIntentResolver.resolve(channel, title, detection.category());
        if (!intent.anyoneEnabled()) {
            return;
        }
        if (Objects.equals(videoId, channel.getLastRecordedVideoId())) {
            return;
        }
        if (!intent.matched()) {
            log.debug("条件に一致する希望者がいないため録画をスキップします: name={}, title={}, カテゴリ={}",
                    channel.getChannelName(), title,
                    detection.category() == null ? "-" : detection.category());
            return;
        }

        // 視聴 URL は検知時に組み立てられている（プラットフォームごとに作り方が違うため）
        boolean started = streamRecorder.startRecording(channel, detection.watchUrl(), videoId, title);
        if (started) {
            DatabaseUpdateVerifier.verify(
                    monitoredChannelRepository.updateLastRecordedVideoId(channel.getId(), videoId),
                    "録画済み動画IDの更新", channel.getId());
        }
        // 起動失敗時は更新しない → 次のサイクルで自動的に再試行される
    }
}
