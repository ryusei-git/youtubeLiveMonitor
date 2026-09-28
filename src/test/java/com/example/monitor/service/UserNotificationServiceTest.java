package com.example.monitor.service;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.NotificationOutcome;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserNotification;
import com.example.monitor.notification.DiscordNotifier;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.UserNotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserNotificationService")
class UserNotificationServiceTest {

    private static final String VIDEO_ID = "video001";
    private static final String ALICE_URL = "https://discord.com/api/webhooks/111111111111111111/alice-token";
    private static final String BOB_URL = "https://discord.com/api/webhooks/222222222222222222/bob-token";

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private CurrentAppUser currentAppUser;

    @Mock
    private UserNotificationRepository userNotificationRepository;

    @Mock
    private DiscordNotifier discordNotifier;

    @Mock
    private AuditLogger auditLogger;

    @InjectMocks
    private UserNotificationService userNotificationService;

    private final MonitoredChannel channel = new MonitoredChannel("UC0000000000000000000001", "テストチャンネル");
    private final LiveStreamDetails liveStream = LiveStreamDetails.builder()
            .videoId(VIDEO_ID)
            .title("配信タイトル")
            .channelTitle("テストチャンネル")
            .watchUrl("https://www.youtube.com/watch?v=video001")
            .build();
    private final AppUser alice = user(1L, "alice", ALICE_URL);
    private final AppUser bob = user(2L, "bob", BOB_URL);

    /** 配信の詳細の Supplier が呼ばれた回数。詳細はクォータを使うので、要らないときに取り出していないかを数える。 */
    private final AtomicInteger detailsCalls = new AtomicInteger();

    private Supplier<Optional<LiveStreamDetails>> details(Optional<LiveStreamDetails> value) {
        return () -> {
            detailsCalls.incrementAndGet();
            return value;
        };
    }

    private static AppUser user(long id, String username, String webhookUrl) {
        AppUser user = new AppUser(username, "hash", AppUser.Role.USER);
        user.setId(id);
        user.setDiscordWebhookUrl(webhookUrl);
        return user;
    }

    /** UserNotification には setter が無いので、状態は ReflectionTestUtils で入れる。 */
    private static UserNotification record(AppUser user, long id, int failureCount, LocalDateTime notifiedAt) {
        UserNotification record = new UserNotification(user, VIDEO_ID);
        ReflectionTestUtils.setField(record, "id", id);
        ReflectionTestUtils.setField(record, "failureCount", failureCount);
        ReflectionTestUtils.setField(record, "notifiedAt", notifiedAt);
        return record;
    }

    @Nested
    @DisplayName("notifySubscribers()")
    class NotifySubscribers {

        @Test
        @DisplayName("正常系：まだ送っていない相手には記録の行を作って送り、送れたことを記録する")
        void testMethod01() {
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of(alice));
            when(userNotificationRepository.findByUserAndVideoId(alice, VIDEO_ID)).thenReturn(Optional.empty());
            when(userNotificationRepository.save(any(UserNotification.class))).thenAnswer(invocation -> {
                UserNotification saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", 10L);
                return saved;
            });

            userNotificationService.notifySubscribers(channel, VIDEO_ID, details(Optional.of(liveStream)));

            verify(discordNotifier).sendLiveStartNotification(ALICE_URL, liveStream);
            verify(userNotificationRepository).markNotified(eq(10L), any(LocalDateTime.class));
            verify(userNotificationRepository, never()).recordFailure(any(), any(), any());
            verify(userNotificationRepository, never()).incrementFailureCount(any());
            ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
            verify(userNotificationRepository).save(captor.capture());
            assertThat(captor.getValue().getUser()).isEqualTo(alice);
            assertThat(captor.getValue().getVideoId()).isEqualTo(VIDEO_ID);
            assertThat(captor.getValue().getFailureCount()).isZero();
        }

        @Test
        @DisplayName("正常系：送れた記録がある相手には送らず、詳細も取り出さない")
        void testMethod02() {
            // 巡回のたびに全員へ送り直さない（UserNotification のクラスの JavaDoc）
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of(alice));
            when(userNotificationRepository.findByUserAndVideoId(alice, VIDEO_ID))
                    .thenReturn(Optional.of(record(alice, 10L, 0, LocalDateTime.now())));

            userNotificationService.notifySubscribers(channel, VIDEO_ID, details(Optional.of(liveStream)));

            verifyNoInteractions(discordNotifier);
            assertThat(detailsCalls.get()).isZero();
            verify(userNotificationRepository, never()).save(any());
            verify(userNotificationRepository, never()).markNotified(any(), any());
            verify(userNotificationRepository, never()).recordFailure(any(), any(), any());
            verify(userNotificationRepository, never()).incrementFailureCount(any());
        }

        @Test
        @DisplayName("正常系：失敗が上限の3回に達した相手には送らず、詳細も取り出さない")
        void testMethod03() {
            // 上限は >=。> にすると 4 回目も送る（pitfalls「通知の再試行には上限がある」）
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of(alice));
            when(userNotificationRepository.findByUserAndVideoId(alice, VIDEO_ID))
                    .thenReturn(Optional.of(record(alice, 10L, 3, null)));

            userNotificationService.notifySubscribers(channel, VIDEO_ID, details(Optional.of(liveStream)));

            verifyNoInteractions(discordNotifier);
            assertThat(detailsCalls.get()).isZero();
            verify(userNotificationRepository, never()).save(any());
            verify(userNotificationRepository, never()).markNotified(any(), any());
            verify(userNotificationRepository, never()).recordFailure(any(), any(), any());
            verify(userNotificationRepository, never()).incrementFailureCount(any());
        }

        @Test
        @DisplayName("異常系：2回失敗した相手への3回目の送信が失敗したら、理由を付けて失敗を記録する")
        void testMethod04() {
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of(alice));
            when(userNotificationRepository.findByUserAndVideoId(alice, VIDEO_ID))
                    .thenReturn(Optional.of(record(alice, 10L, 2, null)));
            doThrow(new IllegalStateException("Discord が HTTP 404 を返しました: Unknown Webhook"))
                    .when(discordNotifier).sendLiveStartNotification(ALICE_URL, liveStream);

            userNotificationService.notifySubscribers(channel, VIDEO_ID, details(Optional.of(liveStream)));

            verify(userNotificationRepository).recordFailure(eq(10L), any(LocalDateTime.class),
                    eq("Discord が HTTP 404 を返しました: Unknown Webhook"));
            verify(userNotificationRepository, never()).markNotified(any(), any());
            // 既存の行の記録は save ではなく UPDATE で行う（pitfalls「監視ループから save(entity) を呼ばない」）
            verify(userNotificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系：失敗の理由は200文字で切って記録する")
        void testMethod05() {
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of(alice));
            when(userNotificationRepository.findByUserAndVideoId(alice, VIDEO_ID))
                    .thenReturn(Optional.of(record(alice, 10L, 0, null)));
            doThrow(new IllegalStateException("x".repeat(250)))
                    .when(discordNotifier).sendLiveStartNotification(ALICE_URL, liveStream);

            userNotificationService.notifySubscribers(channel, VIDEO_ID, details(Optional.of(liveStream)));

            verify(userNotificationRepository).recordFailure(eq(10L), any(LocalDateTime.class), eq("x".repeat(200)));
        }

        @Test
        @DisplayName("異常系：1人目への送信が失敗しても、2人目には送って送れたことを記録する")
        void testMethod06() {
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of(alice, bob));
            when(userNotificationRepository.findByUserAndVideoId(alice, VIDEO_ID))
                    .thenReturn(Optional.of(record(alice, 10L, 0, null)));
            when(userNotificationRepository.findByUserAndVideoId(bob, VIDEO_ID))
                    .thenReturn(Optional.of(record(bob, 11L, 0, null)));
            doThrow(new IllegalStateException("Discord が HTTP 404 を返しました: Unknown Webhook"))
                    .when(discordNotifier).sendLiveStartNotification(ALICE_URL, liveStream);
            doNothing().when(discordNotifier).sendLiveStartNotification(BOB_URL, liveStream);

            userNotificationService.notifySubscribers(channel, VIDEO_ID, details(Optional.of(liveStream)));

            verify(userNotificationRepository).recordFailure(eq(10L), any(LocalDateTime.class), any());
            verify(discordNotifier).sendLiveStartNotification(BOB_URL, liveStream);
            verify(userNotificationRepository).markNotified(eq(11L), any(LocalDateTime.class));
            verify(userNotificationRepository, never()).markNotified(eq(10L), any());
        }

        @Test
        @DisplayName("異常系：1人目の記録を読み出せなくても例外を投げず、2人目には送る")
        void testMethod07() {
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of(alice, bob));
            when(userNotificationRepository.findByUserAndVideoId(alice, VIDEO_ID))
                    .thenThrow(new RuntimeException("DB の異常"));
            when(userNotificationRepository.findByUserAndVideoId(bob, VIDEO_ID))
                    .thenReturn(Optional.of(record(bob, 11L, 0, null)));

            assertThatCode(() -> userNotificationService.notifySubscribers(
                    channel, VIDEO_ID, details(Optional.of(liveStream)))).doesNotThrowAnyException();

            verify(discordNotifier).sendLiveStartNotification(BOB_URL, liveStream);
            verify(userNotificationRepository).markNotified(eq(11L), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("異常系：通知の相手を読み出せなくても例外を投げず、詳細も取り出さない")
        void testMethod08() {
            when(appUserRepository.findNotificationTargets(channel)).thenThrow(new RuntimeException("DB の異常"));

            assertThatCode(() -> userNotificationService.notifySubscribers(
                    channel, VIDEO_ID, details(Optional.of(liveStream)))).doesNotThrowAnyException();

            verifyNoInteractions(discordNotifier, userNotificationRepository);
            assertThat(detailsCalls.get()).isZero();
        }

        @Test
        @DisplayName("正常系：通知の相手がいなければ詳細を取り出さない")
        void testMethod09() {
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of());

            userNotificationService.notifySubscribers(channel, VIDEO_ID, details(Optional.of(liveStream)));

            verifyNoInteractions(discordNotifier, userNotificationRepository);
            assertThat(detailsCalls.get()).isZero();
        }

        @Test
        @DisplayName("異常系：詳細が取れなければ送らず、試行の回数だけ数えて失敗の時刻と理由は残さない")
        void testMethod10() {
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of(alice));
            when(userNotificationRepository.findByUserAndVideoId(alice, VIDEO_ID))
                    .thenReturn(Optional.of(record(alice, 10L, 0, null)));

            userNotificationService.notifySubscribers(channel, VIDEO_ID, details(Optional.empty()));

            verifyNoInteractions(discordNotifier);
            verify(userNotificationRepository).incrementFailureCount(10L);
            verify(userNotificationRepository, never()).recordFailure(any(), any(), any());
            verify(userNotificationRepository, never()).markNotified(any(), any());
        }

        @Test
        @DisplayName("異常系：詳細の取り出しで例外が出ても例外を投げず、取れなかったものとして回数だけ数える")
        void testMethod11() {
            when(appUserRepository.findNotificationTargets(channel)).thenReturn(List.of(alice));
            when(userNotificationRepository.findByUserAndVideoId(alice, VIDEO_ID))
                    .thenReturn(Optional.of(record(alice, 10L, 0, null)));
            Supplier<Optional<LiveStreamDetails>> failing = () -> {
                throw new RuntimeException("クォータ切れ");
            };

            assertThatCode(() -> userNotificationService.notifySubscribers(channel, VIDEO_ID, failing))
                    .doesNotThrowAnyException();

            verifyNoInteractions(discordNotifier);
            verify(userNotificationRepository).incrementFailureCount(10L);
            verify(userNotificationRepository, never()).recordFailure(any(), any(), any());
            verify(userNotificationRepository, never()).markNotified(any(), any());
        }
    }

    @Nested
    @DisplayName("registerWebhook()")
    class RegisterWebhook {

        @Test
        @DisplayName("正常系：前後の空白を落としたURLを保存し、前のWebhookの失敗を消して、監査ログにURLを含めず残す")
        void testMethod01() {
            AppUser viewer = user(7L, "viewer", null);
            when(currentAppUser.require()).thenReturn(viewer);

            userNotificationService.registerWebhook("  " + ALICE_URL + "\n");

            verify(appUserRepository).updateDiscordWebhookUrl(7L, ALICE_URL);
            verify(userNotificationRepository).clearFailures(viewer);
            verify(auditLogger).record(AuditAction.NOTIFICATION_SETTING_CHANGE, AuditOutcome.SUCCESS, 7L, "viewer",
                    null, "USER", "viewer", "Discord の Webhook を登録");
        }

        @Test
        @DisplayName("正常系：登録済みなら「更新」として監査ログに残す")
        void testMethod02() {
            AppUser viewer = user(7L, "viewer", null);
            viewer.setDiscordWebhookUrl(BOB_URL);
            when(currentAppUser.require()).thenReturn(viewer);

            userNotificationService.registerWebhook(ALICE_URL);

            verify(appUserRepository).updateDiscordWebhookUrl(7L, ALICE_URL);
            verify(auditLogger).record(AuditAction.NOTIFICATION_SETTING_CHANGE, AuditOutcome.SUCCESS, 7L, "viewer",
                    null, "USER", "viewer", "Discord の Webhook を更新");
        }

        @Test
        @DisplayName("異常系：Discordの Webhook の形でないURLは断り、例外の文言に入力を含めない")
        void testMethod03() {
            assertThatThrownBy(() -> userNotificationService.registerWebhook(
                    "https://example.com/api/webhooks/1/secret-token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Discord の Webhook の URL（https://discord.com/api/webhooks/ で始まるもの）を入力してください")
                    .hasMessageNotContaining("secret-token");

            verifyNoInteractions(currentAppUser, appUserRepository, userNotificationRepository, auditLogger);
        }

        @Test
        @DisplayName("異常系：nullは断る")
        void testMethod04() {
            assertThatThrownBy(() -> userNotificationService.registerWebhook(null))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(currentAppUser, appUserRepository, userNotificationRepository, auditLogger);
        }
    }

    @Nested
    @DisplayName("unregisterWebhook()")
    class UnregisterWebhook {

        @Test
        @DisplayName("正常系：登録済みならURLを消して監査ログに残す")
        void testMethod01() {
            when(currentAppUser.require()).thenReturn(user(7L, "viewer", ALICE_URL));

            userNotificationService.unregisterWebhook();

            verify(appUserRepository).updateDiscordWebhookUrl(7L, null);
            verify(auditLogger).record(AuditAction.NOTIFICATION_SETTING_CHANGE, AuditOutcome.SUCCESS, 7L, "viewer",
                    null, "USER", "viewer", "Discord の Webhook を解除");
        }

        @Test
        @DisplayName("正常系：登録していなければ何もしない")
        void testMethod02() {
            when(currentAppUser.require()).thenReturn(user(7L, "viewer", null));

            userNotificationService.unregisterWebhook();

            verifyNoInteractions(appUserRepository, auditLogger);
        }
    }

    @Nested
    @DisplayName("sendTestNotification()")
    class SendTestNotification {

        @Test
        @DisplayName("正常系：送れたら成功を返す")
        void testMethod01() {
            when(currentAppUser.require()).thenReturn(user(7L, "viewer", ALICE_URL));

            NotificationOutcome outcome = userNotificationService.sendTestNotification();

            assertThat(outcome.successful()).isTrue();
            verify(discordNotifier).sendTestNotification(ALICE_URL);
        }

        @Test
        @DisplayName("異常系：送れなければ例外を投げず、理由を付けた失敗を返す")
        void testMethod02() {
            when(currentAppUser.require()).thenReturn(user(7L, "viewer", ALICE_URL));
            doThrow(new IllegalStateException("Discord が HTTP 404 を返しました: Unknown Webhook"))
                    .when(discordNotifier).sendTestNotification(ALICE_URL);

            NotificationOutcome outcome = userNotificationService.sendTestNotification();

            assertThat(outcome.successful()).isFalse();
            assertThat(outcome.errorMessage()).isEqualTo("Discord が HTTP 404 を返しました: Unknown Webhook");
        }

        @Test
        @DisplayName("異常系：メッセージの無い例外でも理由を空にしない")
        void testMethod03() {
            when(currentAppUser.require()).thenReturn(user(7L, "viewer", ALICE_URL));
            doThrow(new IllegalStateException()).when(discordNotifier).sendTestNotification(ALICE_URL);

            NotificationOutcome outcome = userNotificationService.sendTestNotification();

            assertThat(outcome.errorMessage()).isEqualTo("java.lang.IllegalStateException");
        }

        @Test
        @DisplayName("異常系：Webhookを登録していなければ例外を投げ、送らない")
        void testMethod04() {
            when(currentAppUser.require()).thenReturn(user(7L, "viewer", null));

            assertThatThrownBy(() -> userNotificationService.sendTestNotification())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("テストの通知を送る Webhook が登録されていません");

            verifyNoInteractions(discordNotifier);
        }
    }
}
