package com.example.monitor.service;

import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.notification.DiscordNotifier;
import com.example.monitor.platform.Platform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 配信状態の判定に失敗し続けていたら、管理者の Discord へ 1 度だけ知らせ、判定できるようになったらもう 1 度知らせる。
 *
 * <p>このアプリの検知は YouTube が保証していない HTML 解析に頼っているので、YouTube 側の変更で突然壊れうる
 * （Twitch も認証情報の失効で一括の問い合わせごと失敗する）。そのとき巡回そのものは最後まで回るので
 * {@code GET /api/health} は {@code UP} のままで、ダッシュボードの警告も画面を開くまで見えない。
 * 自宅のサーバーを常に見ている人はいないため、判定の結果を持っている JVM の中から知らせる
 * （{@code /api/health} はログイン無しで読めるので、判定の件数は外に出さない方針）。
 *
 * <h2>知らせる条件は 2 つ</h2>
 * <ul>
 *   <li><b>プラットフォーム単位</b>：同じプラットフォームの登録チャンネルの過半数が判定できなかった巡回が
 *       {@link #PLATFORM_ALERT_CYCLES} 回続いた。HTML の構造変更・認証情報の失効・通信の障害のように、
 *       全体が止まっているときに当たる</li>
 *   <li><b>チャンネル単位</b>：1 つのチャンネルの連続失敗回数（{@code MonitoredChannel.consecutiveDetectionFailures}）が
 *       {@link #CHANNEL_ALERT_FAILURES} 回以上になった。削除・改名されたチャンネルは待っても直らないため</li>
 * </ul>
 * プラットフォームの過半数が判定できなかった巡回では、チャンネル単位の知らせを送らない。全チャンネルが同時に
 * しきい値を越え、チャンネルの数だけ知らせが並ぶのを防ぐため（プラットフォーム単位の知らせで足りる）。
 * そのため登録が 1 件だけのプラットフォームでは、そのチャンネルの削除もプラットフォーム単位として知らせる
 * （本文に「チャンネルの削除」も原因として書いている）。
 *
 * <h2>状態はメモリにだけ持つ</h2>
 * 知らせたかどうかを DB に持たない（スキーマを増やさない）。失敗が続いたまま再起動すると、もう 1 度知らせる
 * （{@link StreamRecorder} の空き容量の知らせと同じ扱い）。チャンネル単位は DB の連続失敗回数を見るので、
 * 再起動後の最初の巡回で送り直す。
 * 送信に失敗しても「知らせた」ことにする。Webhook の設定ミスのような直らない失敗で、毎巡回送り続けないため
 * （空き容量の知らせと同じ）。
 *
 * <p>巡回（{@link com.example.monitor.scheduler.LiveStreamPollingScheduler}）からだけ呼ぶ。巡回は同時に 1 本しか
 * 走らないが、「今すぐチェック」は定期実行と別のスレッドから来るので {@code synchronized} にしている。
 * DB は読み書きしない。渡されたエンティティも書き換えない（巡回と同じく {@code save(entity)} を使わない方針）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DetectionFailureAlerter {

    /**
     * 過半数が判定できなかった巡回が何回続いたら知らせるか。
     *
     * <p>既定の巡回間隔（120 秒）で約 10 分。YouTube の一時的な 5xx や通信の瞬断で知らせないため、
     * 1 回では知らせない。
     */
    static final int PLATFORM_ALERT_CYCLES = 5;

    /**
     * 1 チャンネルの連続失敗が何回になったら知らせるか。
     *
     * <p>既定の巡回間隔で約 1 時間。ダッシュボードは 2 回で警告を出すが、Discord へ送るのは
     * 「待っても直らない」と言える長さにしてから（1 チャンネルの失敗は一時的な通信エラーでも起こるため）。
     */
    static final int CHANNEL_ALERT_FAILURES = 30;

    /** 1 つの知らせに並べるチャンネルの上限。Discord の埋め込みの本文は 4096 文字までで、超えると送信が失敗するため。 */
    private static final int MAX_LISTED_CHANNELS = 10;

    private final DiscordNotifier discordNotifier;

    /** プラットフォームごとの「過半数が判定できなかった巡回」の連続回数。 */
    private final Map<Platform, Integer> failingCycles = new EnumMap<>(Platform.class);

    /** 判定できないことを知らせ済みのプラットフォーム。 */
    private final Set<Platform> alertedPlatforms = EnumSet.noneOf(Platform.class);

    /**
     * 判定できないことを知らせ済みのチャンネルの主キー。
     * 知らせた後で監視から外されたチャンネルの主キーは残るが、多くても登録したことのあるチャンネルの数までなので消さない。
     */
    private final Set<Long> alertedChannelIds = new HashSet<>();

    /**
     * 1 つのプラットフォームの 1 巡回ぶんの判定結果を受け取り、知らせる条件に入った・抜けたら知らせる。
     *
     * @param platform   対象のプラットフォーム
     * @param channels   そのプラットフォームの監視対象（巡回の開始時に読み込んだもの。書き換えない）
     * @param detections 一括の問い合わせの結果。問い合わせ自体が失敗した場合は空
     */
    public synchronized void recordPlatformResult(
            Platform platform, List<MonitoredChannel> channels, Map<String, LiveStreamDetection> detections) {
        // 巡回（pollPlatformGroup）と同じく、応答に含まれないチャンネルは「判定できなかった」に数える
        List<MonitoredChannel> failed = channels.stream()
                .filter(channel -> detections.getOrDefault(channel.getYoutubeChannelId(), LiveStreamDetection.failed())
                        .isDetectionFailed())
                .toList();
        boolean platformFailing = failed.size() * 2 > channels.size();

        checkPlatform(platform, channels.size(), failed.size(), platformFailing);
        checkChannels(channels, failed, platformFailing);
    }

    /**
     * プラットフォーム単位の条件を見る。
     *
     * @param platform        対象のプラットフォーム
     * @param total           そのプラットフォームの監視対象の件数
     * @param failedCount     この巡回で判定できなかった件数
     * @param platformFailing この巡回で過半数が判定できなかったか
     */
    private void checkPlatform(Platform platform, int total, int failedCount, boolean platformFailing) {
        if (!platformFailing) {
            failingCycles.remove(platform);
            if (alertedPlatforms.remove(platform)) {
                send(platform.displayName() + " の配信状態の判定が戻りました（" + total + " 件中 "
                        + failedCount + " 件が判定できていません）。");
            }
            return;
        }
        int cycles = failingCycles.merge(platform, 1, Integer::sum);
        if (cycles >= PLATFORM_ALERT_CYCLES && alertedPlatforms.add(platform)) {
            send(platform.displayName() + " のチャンネルの過半数（" + total + " 件中 " + failedCount
                    + " 件）で、配信状態の判定に " + cycles + " 巡回続けて失敗しています。"
                    + platform.displayName() + " 側の変更・認証情報の失効・通信の障害（登録が少ないときはチャンネルの削除）で、"
                    + "配信開始の通知と録画が止まっている可能性があります。ダッシュボードの警告と logs/service-app.log を"
                    + "確認してください。");
        }
    }

    /**
     * チャンネル単位の条件を見る。
     *
     * @param channels        そのプラットフォームの監視対象
     * @param failed          この巡回で判定できなかったチャンネル
     * @param platformFailing この巡回で過半数が判定できなかったか。{@code true} なら新しくは知らせない
     */
    private void checkChannels(List<MonitoredChannel> channels, List<MonitoredChannel> failed, boolean platformFailing) {
        Set<Long> failedIds = failed.stream().map(MonitoredChannel::getId).collect(Collectors.toSet());

        List<MonitoredChannel> recovered = new ArrayList<>();
        for (MonitoredChannel channel : channels) {
            if (!failedIds.contains(channel.getId()) && alertedChannelIds.remove(channel.getId())) {
                recovered.add(channel);
            }
        }
        if (!recovered.isEmpty()) {
            send("次のチャンネルの配信状態の判定が戻りました: " + describe(recovered));
        }

        if (platformFailing) {
            return;
        }
        List<MonitoredChannel> newlyFailing = new ArrayList<>();
        for (MonitoredChannel channel : failed) {
            // エンティティは巡回の開始時に読んだもので、この巡回の失敗（recordDetectionFailure の +1）を含まない
            if (channel.getConsecutiveDetectionFailures() + 1 >= CHANNEL_ALERT_FAILURES
                    && alertedChannelIds.add(channel.getId())) {
                newlyFailing.add(channel);
            }
        }
        if (!newlyFailing.isEmpty()) {
            send("次のチャンネルで、配信状態の判定に " + CHANNEL_ALERT_FAILURES + " 回以上続けて失敗しています。"
                    + "チャンネルが削除・改名された可能性があります。管理画面のチャンネル一覧で確かめてください: "
                    + describe(newlyFailing));
        }
    }

    /**
     * 知らせに並べるチャンネルの一覧を作る。
     *
     * @param channels 並べるチャンネル（1 件以上）
     * @return 「名前（識別子）」を読点でつないだもの。上限を超えた分は件数だけ書く
     */
    private static String describe(List<MonitoredChannel> channels) {
        String listed = channels.stream()
                .limit(MAX_LISTED_CHANNELS)
                .map(channel -> channel.getChannelName() + "（" + channel.getYoutubeChannelId() + "）")
                .collect(Collectors.joining("、"));
        int rest = channels.size() - MAX_LISTED_CHANNELS;
        return rest > 0 ? listed + " ほか " + rest + " 件" : listed;
    }

    /**
     * 管理者へ知らせる。送れなくても例外は投げない（巡回を止めない）。
     *
     * @param message 知らせる本文
     */
    private void send(String message) {
        log.info("配信状態の判定について管理者へ知らせます: {}", message);
        try {
            discordNotifier.sendAdminAlert(message);
        } catch (RuntimeException e) {
            // 例外のメッセージに Webhook の URL は入らない（DiscordNotifier.post の JavaDoc 参照）
            log.warn("配信状態の判定の知らせを管理者へ送れませんでした: {}", e.getMessage());
        }
    }
}
