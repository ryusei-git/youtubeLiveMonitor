package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * セッションの失効（無効化・パスワードの変更）、再設定のリンクの 1 回限り・期限、通知の相手の絞り込みは、JPQL の条件の中にしか書かれていない。
 * {@code ActiveAppUserFilterTest} などはこのリポジトリをモックにしている。
 * {@code AppUserManagementControllerTest} は無効化・削除の後の失効を通しで確かめているが、パスワードの変更時刻の比較は通らない。
 * そのため、比較の向き（{@code <=}・{@code >}）や {@code null} の扱いが変わっても気付けない。{@code @DataJpaTest} でインメモリ H2 に実際に流して確かめる。
 */
@DataJpaTest
@DisplayName("AppUserRepository")
class AppUserRepositoryTest {

    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 9, 27, 12, 0);
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1/token";

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private MonitoredChannelRepository monitoredChannelRepository;

    @Autowired
    private UserSubscriptionRepository userSubscriptionRepository;

    @Autowired
    private TestEntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private AppUser newUser(String username) {
        return new AppUser(username, "hashed-password", AppUser.Role.USER);
    }

    private MonitoredChannel persistChannel(String youtubeChannelId, String channelName) {
        return monitoredChannelRepository.save(new MonitoredChannel(youtubeChannelId, channelName));
    }

    private AppUser reload(AppUser user) {
        flushAndClear();
        return appUserRepository.findById(user.getId()).orElseThrow();
    }

    @Nested
    @DisplayName("isSessionValid()")
    class IsSessionValid {

        @Test
        @DisplayName("正常系：パスワードを一度も変えていない利用者は、変更前のログインでも有効")
        void testMethod01() {
            AppUser user = appUserRepository.save(newUser("alice"));

            assertThat(appUserRepository.isSessionValid(user.getId(), null)).isTrue();
        }

        @Test
        @DisplayName("正常系：ログイン時点の変更時刻と同じか、それより後にログインしていれば有効")
        void testMethod02() {
            AppUser user = newUser("alice");
            user.setPasswordChangedAt(CHANGED_AT);
            AppUser saved = appUserRepository.save(user);

            assertThat(appUserRepository.isSessionValid(saved.getId(), CHANGED_AT)).isTrue();
            assertThat(appUserRepository.isSessionValid(saved.getId(), CHANGED_AT.plusMinutes(1))).isTrue();
        }

        @Test
        @DisplayName("異常系：ログインの後にパスワードを変えると失効する")
        void testMethod03() {
            AppUser user = appUserRepository.save(newUser("alice"));
            appUserRepository.updatePassword(user.getId(), "new-hash", CHANGED_AT);

            assertThat(appUserRepository.isSessionValid(user.getId(), CHANGED_AT.minusMinutes(1))).isFalse();
        }

        @Test
        @DisplayName("異常系：パスワードを変える前にログインしたセッション（変更時刻が null）は、変更の後に失効する")
        void testMethod04() {
            AppUser user = newUser("alice");
            user.setPasswordChangedAt(CHANGED_AT);
            AppUser saved = appUserRepository.save(user);

            assertThat(appUserRepository.isSessionValid(saved.getId(), null)).isFalse();
        }

        @Test
        @DisplayName("異常系：無効化された利用者は失効する")
        void testMethod05() {
            AppUser user = newUser("alice");
            user.setEnabled(false);
            AppUser saved = appUserRepository.save(user);

            assertThat(appUserRepository.isSessionValid(saved.getId(), null)).isFalse();
        }

        @Test
        @DisplayName("異常系：存在しない利用者は失効する")
        void testMethod06() {
            assertThat(appUserRepository.isSessionValid(Long.MAX_VALUE, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("findNotificationTargets()")
    class FindNotificationTargets {

        @Test
        @DisplayName("正常系：購読していて通知を切っておらず、有効で Webhook を登録した利用者だけを返す")
        void testMethod01() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            MonitoredChannel channelB = persistChannel("UCbbbbbbbbbbbbbbbbbbbbbb", "Channel B");

            AppUser alice = newUser("alice");
            alice.setDiscordWebhookUrl(WEBHOOK_URL);
            alice = appUserRepository.save(alice);
            userSubscriptionRepository.save(UserSubscription.builder().user(alice).channel(channelA).build());

            AppUser bob = newUser("bob");
            bob.setDiscordWebhookUrl(WEBHOOK_URL);
            bob = appUserRepository.save(bob);
            userSubscriptionRepository.save(UserSubscription.builder().user(bob).channel(channelA)
                    .notifyEnabled(false).build());

            AppUser carol = newUser("carol");
            carol.setDiscordWebhookUrl(WEBHOOK_URL);
            carol.setEnabled(false);
            carol = appUserRepository.save(carol);
            userSubscriptionRepository.save(UserSubscription.builder().user(carol).channel(channelA).build());

            AppUser dave = appUserRepository.save(newUser("dave"));
            userSubscriptionRepository.save(UserSubscription.builder().user(dave).channel(channelA).build());

            AppUser erin = newUser("erin");
            erin.setDiscordWebhookUrl(WEBHOOK_URL);
            erin = appUserRepository.save(erin);
            userSubscriptionRepository.save(UserSubscription.builder().user(erin).channel(channelB).build());

            assertThat(appUserRepository.findNotificationTargets(channelA))
                    .extracting(AppUser::getUsername)
                    .containsExactly("alice");
        }

        @Test
        @DisplayName("正常系：購読者のいないチャンネルでは空を返す")
        void testMethod02() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");

            assertThat(appUserRepository.findNotificationTargets(channelA)).isEmpty();
        }
    }

    @Nested
    @DisplayName("resetPasswordByToken()")
    class ResetPasswordByToken {

        private AppUser persistUserWithToken(LocalDateTime expiresAt) {
            AppUser user = newUser("alice");
            user.setPasswordResetToken("reset-token");
            user.setPasswordResetExpiresAt(expiresAt);
            return appUserRepository.save(user);
        }

        @Test
        @DisplayName("正常系：期限内のリンクなら、パスワードと変更時刻を書き、リンクと期限を消す")
        void testMethod01() {
            AppUser user = persistUserWithToken(CHANGED_AT.plusHours(1));

            int updated = appUserRepository.resetPasswordByToken("reset-token", "new-hash", CHANGED_AT, CHANGED_AT);

            AppUser reloaded = reload(user);
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getPasswordHash()).isEqualTo("new-hash");
            assertThat(reloaded.getPasswordChangedAt()).isEqualTo(CHANGED_AT);
            assertThat(reloaded.getPasswordResetToken()).isNull();
            assertThat(reloaded.getPasswordResetExpiresAt()).isNull();
        }

        @Test
        @DisplayName("異常系：同じリンクは 2 回目には使えない")
        void testMethod02() {
            AppUser user = persistUserWithToken(CHANGED_AT.plusHours(1));

            int first = appUserRepository.resetPasswordByToken("reset-token", "new-hash", CHANGED_AT, CHANGED_AT);
            int second = appUserRepository.resetPasswordByToken("reset-token", "other-hash", CHANGED_AT, CHANGED_AT);

            AppUser reloaded = reload(user);
            assertThat(first).isEqualTo(1);
            assertThat(second).isZero();
            assertThat(reloaded.getPasswordHash()).isEqualTo("new-hash");
        }

        @Test
        @DisplayName("異常系：期限ちょうどのリンクでは書き換えない")
        void testMethod03() {
            AppUser user = persistUserWithToken(CHANGED_AT);

            int updated = appUserRepository.resetPasswordByToken("reset-token", "new-hash", CHANGED_AT, CHANGED_AT);

            AppUser reloaded = reload(user);
            assertThat(updated).isZero();
            assertThat(reloaded.getPasswordHash()).isEqualTo("hashed-password");
            assertThat(reloaded.getPasswordChangedAt()).isNull();
            assertThat(reloaded.getPasswordResetToken()).isEqualTo("reset-token");
        }
    }
}
