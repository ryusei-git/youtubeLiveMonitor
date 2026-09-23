package com.example.monitor.service;

import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.dto.SubscribedChannelResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.platform.Platform;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.UserSubscriptionRepository;
import com.example.monitor.util.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    private final UserSubscriptionRepository userSubscriptionRepository;
    private final AppUserRepository appUserRepository;
    private final MonitoredChannelRepository monitoredChannelRepository;
    private final RecordingRepository recordingRepository;
    private final MonitoredChannelService monitoredChannelService;

    /**
     * ログイン中の利用者が購読しているチャンネルを返す。
     *
     * @return 購読しているチャンネル（購読した順に新しいものから）
     */
    @Transactional(readOnly = true)
    public List<SubscribedChannelResponse> listMySubscriptions() {
        return userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(currentUser()).stream()
                .map(SubscribedChannelResponse::from)
                .toList();
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
     * @return 追加された購読
     * @throws ChannelAlreadyRegisteredException 既に自分が購読している場合
     * @throws IllegalArgumentException          入力に該当するチャンネルが見つからない場合
     */
    @Transactional
    public SubscribedChannelResponse subscribe(Platform platform, String channelInput, String channelName) {
        AppUser user = currentUser();
        MonitoredChannel channel = monitoredChannelService.findOrRegister(platform, channelInput, channelName);

        if (userSubscriptionRepository.existsByUserAndChannel(user, channel)) {
            throw new ChannelAlreadyRegisteredException(channel.getYoutubeChannelId());
        }

        // 位置引数のコンストラクタは項目が増えるたびに呼び出し側が壊れるため使わない
        UserSubscription subscription = new UserSubscription();
        subscription.setUser(user);
        subscription.setChannel(channel);
        UserSubscription saved = userSubscriptionRepository.save(subscription);
        log.info("チャンネルを購読しました: user={}, channel={}", user.getUsername(), channel.getYoutubeChannelId());
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
        AppUser user = currentUser();
        return monitoredChannelRepository.findById(channelId)
                .flatMap(channel -> userSubscriptionRepository.findByUserAndChannel(user, channel))
                .map(subscription -> {
                    subscription.setRecordEnabled(enabled);
                    subscription.setRecordTitleKeywords(
                            titleKeywords == null || titleKeywords.isBlank() ? null : titleKeywords.trim());
                    UserSubscription saved = userSubscriptionRepository.save(subscription);
                    log.info("購読の録画設定を変更しました: user={}, channelId={}, enabled={}, keywords={}",
                            user.getUsername(), channelId, enabled, saved.getRecordTitleKeywords());
                    return SubscribedChannelResponse.from(saved);
                });
    }

    /**
     * ログイン中の利用者が購読しているチャンネルの録画を返す。
     *
     * <p><b>購読していないチャンネルの録画は返さない。</b>録画ファイルは配信者の映像そのものなので、
     * 見せる範囲は本人が購読しているものに限る。チャンネルに紐づかないダウンロード
     * （管理者が URL を貼って取得したもの）も対象外。
     *
     * @param pageable ページ指定
     * @return 録画の一覧
     */
    @Transactional(readOnly = true)
    public Page<RecordingResponse> listMyRecordings(Pageable pageable) {
        List<MonitoredChannel> channels = userSubscriptionRepository
                .findByUserOrderBySubscribedAtDesc(currentUser()).stream()
                .map(UserSubscription::getChannel)
                .toList();

        if (channels.isEmpty()) {
            return Page.empty(pageable);
        }
        return recordingRepository.findByChannelInOrderByStartedAtDesc(channels, pageable)
                .map(RecordingResponse::from);
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
        AppUser user = currentUser();
        // 主キーだけ詰めた仮のインスタンスは作らない。エンティティの作り
        // （コンストラクタや setter の有無）に依存して壊れやすいため
        MonitoredChannel channel = monitoredChannelRepository.findById(channelId).orElse(null);
        if (channel == null) {
            return false;
        }

        int removed = userSubscriptionRepository.deleteByUserAndChannel(user, channel);
        if (removed > 0) {
            log.info("チャンネルの購読を解除しました: user={}, channelId={}", user.getUsername(), channelId);
        }
        return removed > 0;
    }

    /**
     * ログイン中の利用者を取得する。
     *
     * <p>認証を必須にしている経路からしか呼ばれないため、取得できない場合は
     * 想定外の状態として例外にする（誰の購読か分からないまま処理を続けると、
     * 別の利用者のデータを操作しかねない）。
     *
     * @return ログイン中の利用者
     */
    private AppUser currentUser() {
        String username = RequestContext.currentUsername();
        if (username == null) {
            throw new IllegalStateException("ログイン情報を特定できませんでした");
        }
        return appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("ログイン中の利用者が見つかりません: " + username));
    }
}
