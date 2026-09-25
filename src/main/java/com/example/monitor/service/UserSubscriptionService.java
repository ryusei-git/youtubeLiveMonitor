package com.example.monitor.service;

import com.example.monitor.dto.SubscribedChannelResponse;
import com.example.monitor.dto.UpcomingStreamResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.platform.Platform;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.UserSubscriptionRepository;
import com.example.monitor.util.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 利用者ごとの「購読しているチャンネル」を扱う。
 *
 * <h2>管理者のチャンネル管理とは別物</h2>
 * 管理者画面（{@code channels.html}）が扱うのは<b>監視対象そのもの</b>で、消せば
 * 全利用者の監視が止まり、通知履歴・録画履歴も連鎖削除される。
 * こちらが扱うのは<b>誰がどれを見たいか</b>だけで、
 * <b>解除しても消えるのは自分の購読 1 行だけ</b>。チャンネル本体・他人の購読・
 * 通知履歴・録画履歴には一切触れない。明確に別物として作っている。
 *
 * <h2>巡回には影響させない</h2>
 * 「購読者ゼロのチャンネルは巡回対象から外す」という案は<b>採用していない</b>。
 * 購読の概念が無い時代に登録されたチャンネルは誰にも紐づいておらず、
 * 購読を巡回の条件にすると<b>それらの監視が即座に全停止する</b>ため。
 * 購読はあくまで「画面に出す範囲」を決めるだけで、巡回・録画・通知は
 * 従来どおりチャンネル単位で動く。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSubscriptionService {

    /**
     * 1 利用者あたりの購読数の上限。
     *
     * <p><b>これが無いと、利用者の操作だけで巡回対象を無制限に増やせる。</b>
     * 未登録のチャンネルを購読すると {@code channels} の行が増え、
     * 巡回のたびにそのぶん問い合わせが走る（巡回時間も外部への通信量も伸びる）。
     *
     * <p>個人と友人が使う規模での上限。足りなくなったら増やせばよいので、
     * 設定項目にはしていない。
     */
    private static final int MAX_SUBSCRIPTIONS_PER_USER = 50;

    private final UserSubscriptionRepository userSubscriptionRepository;
    private final AppUserRepository appUserRepository;
    private final CurrentAppUser currentAppUser;
    private final MonitoredChannelRepository monitoredChannelRepository;
    private final RecordingRepository recordingRepository;
    private final MonitoredChannelService monitoredChannelService;
    private final AuditLogger auditLogger;

    /**
     * ログイン中の利用者が購読しているチャンネルを返す。
     *
     * @return 購読しているチャンネル（購読した順に新しいものから）
     */
    @Transactional(readOnly = true)
    public List<SubscribedChannelResponse> listMySubscriptions() {
        // 件数はチャンネルごとに数えず、全チャンネル分を 1 回の問い合わせで数えて引き当てる
        // （購読数ぶん問い合わせが走るのを避けるため。管理者の一覧と同じやり方）
        Map<Long, Long> recordingCounts = monitoredChannelService.countPlayableRecordingsByChannel();
        return userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(currentAppUser.require()).stream()
                .map(subscription -> SubscribedChannelResponse.from(
                        subscription, recordingCounts.getOrDefault(subscription.getChannel().getId(), 0L)))
                .toList();
    }

    /**
     * ログイン中の利用者が購読しているチャンネルの配信予定を返す。
     *
     * <p>選び方と並びは管理者の配信予定と同じ（{@link UpcomingStreamResponse#listWithinWindow} を共有する）で、
     * <b>対象を購読しているチャンネルに絞る</b>だけ。
     * 予定は巡回時にチャンネルへ記録済みのものを読むため、外部への問い合わせは発生しない。
     *
     * @return 直近 7 日以内に開始予定の配信（開始予定の早い順）
     */
    @Transactional(readOnly = true)
    public List<UpcomingStreamResponse> listMyUpcomingStreams() {
        return UpcomingStreamResponse.listWithinWindow(
                userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(currentAppUser.require()).stream()
                        .map(UserSubscription::getChannel)
                        .toList());
    }

    /**
     * ログイン中の利用者の購読を追加する。
     *
     * <p>監視対象として未登録なら、このとき併せて登録する
     * （{@link MonitoredChannelService#findOrRegister}）。既に登録済みなら
     * <b>そのチャンネルを共有して購読だけを増やす</b>——巡回を二重に走らせないため。
     *
     * @param platform     プラットフォーム
     * @param channelInput 利用者の入力（チャンネル ID・ハンドル・ログイン名・URL）
     * @param channelName  新規登録時に使う表示名
     * @param recordEnabled 自動録画を希望するか
     * @return 追加された購読
     * @throws ChannelAlreadyRegisteredException 既に自分が購読している場合
     * @throws IllegalArgumentException          入力に該当するチャンネルが見つからない場合
     */
    @Transactional
    public SubscribedChannelResponse subscribe(Platform platform, String channelInput, String channelName,
                                               boolean recordEnabled) {
        AppUser user = currentAppUser.require();

        // 上限の判定は findOrRegister より前に行う。後ろに置くと、上限に達した利用者でも
        // 未登録チャンネルの行だけが増えてしまい、巡回対象が無制限に伸びる
        // （このチェックが守りたいのはまさにそこ）
        long owned = userSubscriptionRepository.countByUser(user);
        if (owned >= MAX_SUBSCRIPTIONS_PER_USER) {
            throw new IllegalArgumentException(
                    "購読できるチャンネルは" + MAX_SUBSCRIPTIONS_PER_USER + "件までです。"
                            + "不要なチャンネルを解除してから追加してください。");
        }

        MonitoredChannel channel = monitoredChannelService.findOrRegister(platform, channelInput, channelName);

        if (userSubscriptionRepository.existsByUserAndChannel(user, channel)) {
            throw new ChannelAlreadyRegisteredException(channel.getYoutubeChannelId());
        }

        // 位置引数のコンストラクタは項目が増えるたびに呼び出し側が壊れるため使わない
        UserSubscription subscription = new UserSubscription();
        subscription.setUser(user);
        subscription.setChannel(channel);
        subscription.setRecordEnabled(recordEnabled);
        UserSubscription saved = userSubscriptionRepository.save(subscription);
        log.info("チャンネルを購読しました: user={}, channel={}", user.getUsername(), channel.getYoutubeChannelId());
        auditLogger.record(AuditAction.CHANNEL_SUBSCRIBE, AuditOutcome.SUCCESS, user.getId(), user.getUsername(),
                null, "CHANNEL", String.valueOf(channel.getId()), "channel=" + channel.getYoutubeChannelId() + ", record=" + recordEnabled);
        return SubscribedChannelResponse.from(saved);
    }

    /**
     * ログイン中の利用者の、このチャンネルに対する録画の希望を変更する。
     *
     * <p><b>変わるのは自分の購読だけ。</b>チャンネル単位の設定にも他の購読者にも触れない。
     * 実際に録画されるかは「誰か 1 人でも希望していれば録画する」で決まるため、
     * <b>自分が OFF にしても、他に希望者がいれば録画は続く</b>
     * （{@link RecordingIntentResolver} 参照）。逆に言えば、自分が ON にすれば
     * 他の人の設定に関係なく録画される。
     *
     * @param channelId     対象チャンネルの主キー
     * @param enabled       自動録画を希望するか
     * @param titleKeywords 絞り込むキーワード。空なら絞り込みなし
     * @return 変更後の購読。購読していなければ {@link Optional#empty()}
     */
    @Transactional
    public Optional<SubscribedChannelResponse> updateRecordSetting(
            Long channelId, boolean enabled, String titleKeywords) {
        AppUser user = currentAppUser.require();
        return monitoredChannelRepository.findById(channelId)
                .flatMap(channel -> userSubscriptionRepository.findByUserAndChannel(user, channel))
                .map(subscription -> {
                    subscription.setRecordEnabled(enabled);
                    subscription.setRecordTitleKeywords(
                            titleKeywords == null || titleKeywords.isBlank() ? null : titleKeywords.trim());
                    UserSubscription saved = userSubscriptionRepository.save(subscription);
                    log.info("購読の録画設定を変更しました: user={}, channelId={}, enabled={}, keywords={}",
                            user.getUsername(), channelId, enabled, saved.getRecordTitleKeywords());
                    auditLogger.record(AuditAction.CHANNEL_SETTING_CHANGE, AuditOutcome.SUCCESS,
                            user.getId(), user.getUsername(), null, "CHANNEL", String.valueOf(channelId),
                            "enabled=" + enabled + ", keywords=" + saved.getRecordTitleKeywords());
                    return SubscribedChannelResponse.from(saved);
                });
    }

    /**
     * ログイン中の利用者が購読しているチャンネルの録画を 1 件取得する。
     *
     * <p><b>購読していない録画は「存在しない」と同じ扱い（404）にする。</b>403 にすると、
     * ID を順に試すだけで購読外の録画がどれだけあるかが分かってしまうため。
     * チャンネルに紐づかない録画（管理者が URL を貼って取得したもの）も同じく見せない。
     *
     * <p>再生画面での 1 件取得と、視聴済み・お気に入りの印を付ける前の確認に使う。
     *
     * @param recordingId 録画の主キー
     * @return 該当する録画
     * @throws RecordingNotFoundException 録画が無いか、購読していないチャンネルの録画の場合
     */
    @Transactional(readOnly = true)
    public Recording findMyRecording(Long recordingId) {
        AppUser user = currentAppUser.require();
        return recordingRepository.findById(recordingId)
                .filter(recording -> recording.getChannel() != null
                        && userSubscriptionRepository.existsByUserAndChannel(user, recording.getChannel()))
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
    }

    /**
     * ログイン中の利用者が、そのチャンネルの録画を見てよいかを返す。
     *
     * <p>録画ファイルの配信（{@code /recordings/**}）の可否判定に使う。
     *
     * @param youtubeChannelId プラットフォームが発行するチャンネル識別子
     * @return 購読していれば {@code true}
     */
    @Transactional(readOnly = true)
    public boolean canAccessChannelRecordings(String youtubeChannelId) {
        String username = RequestContext.currentUsername();
        if (username == null) {
            return false;
        }
        return appUserRepository.findByUsername(username)
                .flatMap(user -> monitoredChannelRepository.findByYoutubeChannelId(youtubeChannelId)
                        .map(channel -> userSubscriptionRepository.existsByUserAndChannel(user, channel)))
                .orElse(false);
    }

    /**
     * ログイン中の利用者の購読を解除する。
     *
     * <p><b>消えるのは自分の購読 1 行だけ。</b>チャンネル本体は残るので、
     * 他の利用者の購読も、通知履歴・録画履歴・録画ファイルも影響を受けない。
     *
     * @param channelId 解除するチャンネルの主キー
     * @return 実際に解除できたなら {@code true}。購読していなければ {@code false}
     */
    @Transactional
    public boolean unsubscribe(Long channelId) {
        AppUser user = currentAppUser.require();
        // 主キーだけ詰めた仮のインスタンスは作らない。エンティティの作り
        // （コンストラクタや setter の有無）に依存して壊れやすいため
        MonitoredChannel channel = monitoredChannelRepository.findById(channelId).orElse(null);
        if (channel == null) {
            return false;
        }

        int removed = userSubscriptionRepository.deleteByUserAndChannel(user, channel);
        if (removed > 0) {
            log.info("チャンネルの購読を解除しました: user={}, channelId={}", user.getUsername(), channelId);
            auditLogger.record(AuditAction.CHANNEL_UNSUBSCRIBE, AuditOutcome.SUCCESS, user.getId(),
                    user.getUsername(), null, "CHANNEL", String.valueOf(channelId),
                    "channel=" + channel.getYoutubeChannelId());
        }
        return removed > 0;
    }
}
