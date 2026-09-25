package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.UserNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserNotificationRepository} の更新クエリ・集計クエリを検証する。
 *
 * <p>JPQL の UPDATE と MAX はモックでは確かめられないため、{@link UserSubscriptionRepositoryTest} と同じく
 * {@code @DataJpaTest} でインメモリ H2 に実際に SQL を流す。
 */
@DataJpaTest
@DisplayName("UserNotificationRepository")
class UserNotificationRepositoryTest {

    @Autowired
    private UserNotificationRepository userNotificationRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private TestEntityManager entityManager;

    private AppUser persistUser(String username) {
        return appUserRepository.save(new AppUser(username, "hashed-password", AppUser.Role.USER));
    }

    private UserNotification reload(UserNotification notification) {
        entityManager.clear();
        return userNotificationRepository.findById(notification.getId()).orElseThrow();
    }

    @Nested
    @DisplayName("recordFailure()")
    class RecordFailure {

        @Test
        @DisplayName("正常系：失敗回数を 1 増やし、失敗の時刻と理由を書く")
        void testMethod01() {
            UserNotification record = userNotificationRepository.save(new UserNotification(persistUser("alice"), "v1"));
            LocalDateTime failedAt = LocalDateTime.of(2026, 9, 26, 12, 0);

            int updated = userNotificationRepository.recordFailure(record.getId(), failedAt, "Discord が HTTP 404 を返しました");

            UserNotification reloaded = reload(record);
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getFailureCount()).isEqualTo(1);
            assertThat(reloaded.getLastFailedAt()).isEqualTo(failedAt);
            assertThat(reloaded.getLastError()).isEqualTo("Discord が HTTP 404 を返しました");
        }
    }

    @Nested
    @DisplayName("clearFailures()")
    class ClearFailures {

        @Test
        @DisplayName("正常系：指定した利用者の失敗の時刻と理由だけを消し、失敗回数は残す")
        void testMethod01() {
            UserNotification alice = userNotificationRepository.save(new UserNotification(persistUser("alice"), "v1"));
            UserNotification bob = userNotificationRepository.save(new UserNotification(persistUser("bob"), "v1"));
            LocalDateTime failedAt = LocalDateTime.of(2026, 9, 26, 12, 0);
            userNotificationRepository.recordFailure(alice.getId(), failedAt, "error");
            userNotificationRepository.recordFailure(bob.getId(), failedAt, "error");

            userNotificationRepository.clearFailures(alice.getUser());

            UserNotification reloadedAlice = reload(alice);
            assertThat(reloadedAlice.getLastFailedAt()).isNull();
            assertThat(reloadedAlice.getLastError()).isNull();
            assertThat(reloadedAlice.getFailureCount()).isEqualTo(1);
            assertThat(reload(bob).getLastFailedAt()).isEqualTo(failedAt);
        }
    }

    @Nested
    @DisplayName("findLastDeliveredAt() / findLastFailedAt()")
    class FindLast {

        @Test
        @DisplayName("正常系：利用者の行のうち最も新しい送信成功・失敗の時刻を返し、無ければ null")
        void testMethod01() {
            AppUser alice = persistUser("alice");
            AppUser bob = persistUser("bob");
            UserNotification v1 = userNotificationRepository.save(new UserNotification(alice, "v1"));
            UserNotification v2 = userNotificationRepository.save(new UserNotification(alice, "v2"));
            userNotificationRepository.markNotified(v1.getId(), LocalDateTime.of(2026, 9, 1, 0, 0));
            userNotificationRepository.markNotified(v2.getId(), LocalDateTime.of(2026, 9, 2, 0, 0));
            userNotificationRepository.recordFailure(v1.getId(), LocalDateTime.of(2026, 9, 3, 0, 0), "error");

            assertThat(userNotificationRepository.findLastDeliveredAt(alice)).isEqualTo(LocalDateTime.of(2026, 9, 2, 0, 0));
            assertThat(userNotificationRepository.findLastFailedAt(alice)).isEqualTo(LocalDateTime.of(2026, 9, 3, 0, 0));
            assertThat(userNotificationRepository.findLastDeliveredAt(bob)).isNull();
            assertThat(userNotificationRepository.findLastFailedAt(bob)).isNull();
        }
    }
}
