package com.example.monitor.service;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.NotificationDeliveryStatus;
import com.example.monitor.dto.NotificationOutcome;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserNotification;
import com.example.monitor.notification.DiscordNotifier;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.UserNotificationRepository;
import com.example.monitor.util.DatabaseUpdateVerifier;
import com.example.monitor.util.DiscordWebhookUrl;
import com.example.monitor.util.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 利用者ごとの Discord 通知。Webhook の登録（{@code /api/my/notification-settings}）と、
 * 購読しているチャンネルの配信開始の送信を扱う。
 *
 * <h2>全体向けの通知とは切り離して判定する</h2>
 * 全体向け（{@code .env} の {@code DISCORD_WEBHOOK_URL}）は、チャンネルの「通知済みの動画 ID」・
 * 失敗回数・タイトルフィルターで送るかを決める。利用者向けはそのどれにも従わず、
 * <b>購読しているチャンネルの配信開始はすべて送る</b>（#149。購読ごとの絞り込みは今は作らない）。
 * チャンネルのフィルターは管理者の設定なので、それで利用者に届く通知が変わると、利用者からは理由が見えない。
 * 全体向けの送信が成功したかどうかにも左右されないので、利用者への送信だけが失敗した場合も送り直せる。
 *
 * <h2>同じ配信を同じ利用者に何度も送らない</h2>
 * 誰にどの配信を送ったかを {@link UserNotification} に組ごとに 1 行残し、送れた組には二度と送らない。
 * 配信中は巡回のたびにここを通るため、この記録が無いと巡回のたびに送ってしまう。
 * ただし送信と記録の間でアプリが止まった場合は、次の巡回でもう 1 回送る（取りこぼすより重複の方が実害が小さいため、この順にしている）。
 *
 * <h2>失敗の扱い</h2>
 * 失敗した利用者には次の巡回で送り直すが、同じ配信について {@link #MAX_ATTEMPTS} 回失敗したら諦める
 * （{@code docs/pitfalls.md}「通知の再試行には上限がある」）。配信が変われば記録の行も別になるので、
 * 回数は配信ごとに自然に数え直しになる。
 * 1 人の失敗で他の利用者への送信・全体向けの通知・録画を止めないよう、例外はこのクラスの中で止めて WARN に残す。
 *
 * <h2>Webhook の URL は秘密</h2>
 * URL を知っていれば誰でもその Discord のチャンネルへ書き込めるため、API では登録済みかどうかしか返さず、
 * ログにも監査ログにも出さない。受け付けるのは Discord の Webhook の形だけ（{@link DiscordWebhookUrl}）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserNotificationService {

    /**
     * 1 つの配信について、1 人の利用者へ送信を試みる上限回数。
     *
     * <p>全体向け（{@code LiveStreamPollingScheduler} の上限）と同じ回数。Webhook を Discord 側で消した・
     * 打ち間違えたといった直らない失敗で、配信が続く限り巡回のたびに送り続けないため。
     */
    private static final int MAX_ATTEMPTS = 3;

    /** 失敗の理由を残す長さ。{@link UserNotification} の {@code lastError} 列の長さと揃える。 */
    private static final int MAX_ERROR_LENGTH = 200;

    /** 監査ログの対象の種類。利用者管理の監査ログと揃える。 */
    private static final String AUDIT_TARGET_TYPE = "USER";

    private final AppUserRepository appUserRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final DiscordNotifier discordNotifier;
    private final AuditLogger auditLogger;

    /**
     * ログイン中の利用者が Webhook を登録しているかを返す。URL そのものは返さない。
     *
     * @return 登録していれば {@code true}
     */
    public boolean isWebhookConfigured() {
        return currentUser().getDiscordWebhookUrl() != null;
    }

    /**
     * ログイン中の利用者へ、最後に通知を届けた時刻と最後に送れなかった時刻を返す。
     *
     * <p>Webhook を Discord 側で消された利用者は、以後の配信で毎回黙って失敗し続け、
     * 「最近配信が無いだけ」と区別できない。画面で気付けるようにするために返す。
     *
     * @return 通知が届いているかの状況
     */
    public NotificationDeliveryStatus getDeliveryStatus() {
        AppUser user = currentUser();
        return new NotificationDeliveryStatus(userNotificationRepository.findLastDeliveredAt(user),
                userNotificationRepository.findLastFailedAt(user));
    }

    /**
     * ログイン中の利用者の Webhook を登録する。登録済みなら置き換える。
     *
     * @param webhookUrl 登録する Discord の Webhook の URL
     * @throws IllegalArgumentException Discord の Webhook の URL ではない場合
     */
    public void registerWebhook(String webhookUrl) {
        String url = webhookUrl == null ? null : webhookUrl.strip();
        if (!DiscordWebhookUrl.isValid(url)) {
            // 入力をメッセージに入れない。GlobalExceptionHandler がメッセージをログに残すため、
            // 打ち間違えた本物の URL（＝秘密）がログに出てしまう
            throw new IllegalArgumentException("Discord の Webhook の URL"
                    + "（https://discord.com/api/webhooks/ で始まるもの）を入力してください");
        }
        AppUser user = currentUser();
        String change = user.getDiscordWebhookUrl() == null ? "登録" : "更新";
        DatabaseUpdateVerifier.verify(appUserRepository.updateDiscordWebhookUrl(user.getId(), url),
                "Discord の Webhook の" + change, user.getId());
        // 前の Webhook での失敗を、新しい Webhook が届いていない印に見せない
        userNotificationRepository.clearFailures(user);
        log.info("Discord の Webhook を{}しました: user={}", change, user.getUsername());
        auditLogger.record(AuditAction.NOTIFICATION_SETTING_CHANGE, AuditOutcome.SUCCESS, user.getId(),
                user.getUsername(), null, AUDIT_TARGET_TYPE, user.getUsername(), "Discord の Webhook を" + change);
    }

    /**
     * ログイン中の利用者の Webhook を解除する。以後この利用者には通知を送らない。
     * 登録していなければ何もしない。
     */
    public void unregisterWebhook() {
        AppUser user = currentUser();
        if (user.getDiscordWebhookUrl() == null) {
            return;
        }
        DatabaseUpdateVerifier.verify(appUserRepository.updateDiscordWebhookUrl(user.getId(), null),
                "Discord の Webhook の解除", user.getId());
        log.info("Discord の Webhook を解除しました: user={}", user.getUsername());
        auditLogger.record(AuditAction.NOTIFICATION_SETTING_CHANGE, AuditOutcome.SUCCESS, user.getId(),
                user.getUsername(), null, AUDIT_TARGET_TYPE, user.getUsername(), "Discord の Webhook を解除");
    }

    /**
     * ログイン中の利用者の Webhook へテストの通知を 1 件送る。
     *
     * @return 送信の結果。失敗時の理由に URL は含まない
     * @throws IllegalArgumentException Webhook を登録していない場合
     */
    public NotificationOutcome sendTestNotification() {
        AppUser user = currentUser();
        String url = user.getDiscordWebhookUrl();
        if (url == null) {
            throw new IllegalArgumentException("テストの通知を送る Webhook が登録されていません");
        }
        NotificationOutcome outcome = attempt(() -> discordNotifier.sendTestNotification(url));
        if (outcome.successful()) {
            log.info("テストの通知を送りました: user={}", user.getUsername());
        } else {
            log.warn("テストの通知を送れませんでした: user={}, reason={}", user.getUsername(), outcome.errorMessage());
        }
        return outcome;
    }

    /**
     * 配信中のチャンネルを購読していて Webhook を登録している利用者へ、まだ送っていなければ配信開始を送る。
     * 巡回が配信中を検知するたびに呼ぶ。
     *
     * <p><b>例外を投げない。</b>全体向けの通知・録画は、この後に同じ巡回の中で続くため。
     *
     * <p>詳細は送る相手がいるときだけ取り出す（YouTube では取得にクォータを使うため、
     * 全員に送り終えた配信で毎巡回取らない）。
     *
     * @param channel 配信中のチャンネル
     * @param videoId 配信の動画 ID
     * @param details 通知の本文に使う配信の詳細。必要になった時点で取り出す
     */
    public void notifySubscribers(MonitoredChannel channel, String videoId,
                                  Supplier<Optional<LiveStreamDetails>> details) {
        List<AppUser> targets;
        try {
            targets = appUserRepository.findNotificationTargets(channel);
        } catch (RuntimeException e) {
            log.warn("利用者向けの通知の相手を読み出せませんでした。全体向けの通知・録画は続けます: video={}", videoId, e);
            return;
        }
        // ponytail: 相手ごとに巡回の中で順に送る。利用者が増えて巡回が延びるようなら非同期にする
        for (AppUser user : targets) {
            try {
                notifyUser(user, videoId, details);
            } catch (RuntimeException e) {
                // 記録の読み書きの異常など。1 人の異常で他の利用者への送信を止めない
                log.warn("利用者への通知を処理できませんでした: user={}, video={}", user.getUsername(), videoId, e);
            }
        }
    }

    /**
     * 1 人の利用者へ、まだ送っていなければ配信開始を送り、結果を記録する。
     *
     * <p>記録の行は送る前に作る。送れたかどうかに関わらず、この組の状態を 1 行で持つため
     * （送った直後にアプリが止まっても、行が「未送信」のまま残って次の巡回で送り直せる）。
     *
     * @param user    通知の相手
     * @param videoId 配信の動画 ID
     * @param details 配信の詳細
     */
    private void notifyUser(AppUser user, String videoId, Supplier<Optional<LiveStreamDetails>> details) {
        // ponytail: 送った記録の行は消さない（1 利用者・1 配信で 1 行と小さい）。増えて困るなら古い行を消す処理を足す
        UserNotification record = userNotificationRepository.findByUserAndVideoId(user, videoId)
                .orElseGet(() -> userNotificationRepository.save(new UserNotification(user, videoId)));
        if (record.getNotifiedAt() != null || record.getFailureCount() >= MAX_ATTEMPTS) {
            return;
        }

        NotificationOutcome outcome = attempt(() -> discordNotifier.sendLiveStartNotification(
                user.getDiscordWebhookUrl(),
                details.get().orElseThrow(() -> new IllegalStateException("配信の詳細を取得できませんでした"))));

        if (outcome.successful()) {
            DatabaseUpdateVerifier.verify(
                    userNotificationRepository.markNotified(record.getId(), LocalDateTime.now()),
                    "利用者への通知済みの記録", record.getId());
            log.info("利用者へ配信開始を通知しました: user={}, video={}", user.getUsername(), videoId);
            return;
        }

        String error = outcome.errorMessage();
        DatabaseUpdateVerifier.verify(userNotificationRepository.recordFailure(record.getId(), LocalDateTime.now(),
                        error != null && error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error),
                "利用者への通知の失敗の記録", record.getId());
        int failures = record.getFailureCount() + 1;
        log.warn("利用者への通知に失敗しました（{}/{} 回目{}）: user={}, video={}, reason={}",
                failures, MAX_ATTEMPTS, failures >= MAX_ATTEMPTS ? "。この配信への送信は諦めます" : "",
                user.getUsername(), videoId, outcome.errorMessage());
    }

    /**
     * 送信を試み、例外を結果に変える。
     *
     * @param send 送信の処理
     * @return 送信の結果
     */
    private static NotificationOutcome attempt(Runnable send) {
        try {
            send.run();
            return NotificationOutcome.success();
        } catch (RuntimeException e) {
            // メッセージの無い例外でも理由が空にならないようにする（API の応答にも使うため）
            return NotificationOutcome.failure(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /**
     * ログイン中の利用者を取得する。
     *
     * <p>認証を必須にしている経路からしか呼ばれないため、取得できなければ想定外として例外にする
     * （誰の設定か分からないまま書き込むと、別の利用者のデータを変えかねない）。
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
