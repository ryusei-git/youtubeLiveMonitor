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
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UserSubscriptionRepository} の永続化・制約・クエリを検証する。
 *
 * <p>一意制約と連鎖削除は DB のスキーマ・FK 制約という「実際に生成された DDL」に依存する
 * 挙動であり、モックでは確認できない。{@link AuditLogRepositoryTest} と同じ理由で
 * {@code @DataJpaTest} を使い、インメモリ H2 に対して実際に SQL を実行して確認する。
 */
@DataJpaTest
@DisplayName("UserSubscriptionRepository")
class UserSubscriptionRepositoryTest {

    @Autowired
    private UserSubscriptionRepository userSubscriptionRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private MonitoredChannelRepository monitoredChannelRepository;

    @Autowired
    private TestEntityManager entityManager;

    private AppUser persistUser(String username) {
        return appUserRepository.save(new AppUser(username, "hashed-password", AppUser.Role.USER));
    }

    private MonitoredChannel persistChannel(String youtubeChannelId, String channelName) {
        return monitoredChannelRepository.save(new MonitoredChannel(youtubeChannelId, channelName));
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("正常系：同じ利用者でも異なるチャンネルなら購読できる")
        void testMethod01() {
            AppUser user = persistUser("alice");
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            MonitoredChannel channelB = persistChannel("UCbbbbbbbbbbbbbbbbbbbbbb", "Channel B");

            userSubscriptionRepository.save(UserSubscription.builder().user(user).channel(channelA).build());
            UserSubscription second = userSubscriptionRepository.save(
                    UserSubscription.builder().user(user).channel(channelB).build());

            assertThat(second.getId()).isNotNull();
            assertThat(second.getSubscribedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系：同じ利用者が同じチャンネルを二重に購読しようとすると一意制約違反になる")
        void testMethod02() {
            AppUser user = persistUser("alice");
            MonitoredChannel channel = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            userSubscriptionRepository.save(UserSubscription.builder().user(user).channel(channel).build());

            assertThatThrownBy(() -> userSubscriptionRepository.save(
                    UserSubscription.builder().user(user).channel(channel).build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("findByUserOrderBySubscribedAtDesc()")
    class FindByUserOrderBySubscribedAtDesc {

        @Test
        @DisplayName("正常系：指定した利用者の購読だけを、購読日時の新しい順に取得する")
        void testMethod01() throws InterruptedException {
            AppUser alice = persistUser("alice");
            AppUser bob = persistUser("bob");
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            MonitoredChannel channelB = persistChannel("UCbbbbbbbbbbbbbbbbbbbbbb", "Channel B");

            userSubscriptionRepository.save(UserSubscription.builder().user(alice).channel(channelA).build());
            // H2はLocalDateTimeの精度がミリ秒単位のため、順序を安定させるためにわずかに待つ
            Thread.sleep(5);
            UserSubscription aliceSecond = userSubscriptionRepository.save(
                    UserSubscription.builder().user(alice).channel(channelB).build());
            userSubscriptionRepository.save(UserSubscription.builder().user(bob).channel(channelA).build());

            List<UserSubscription> result = userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(alice);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isEqualTo(aliceSecond.getId());
            assertThat(result).allMatch(s -> s.getUser().getId().equals(alice.getId()));
        }

        @Test
        @DisplayName("正常系：購読が1件も無い利用者には空の一覧を返す")
        void testMethod02() {
            AppUser user = persistUser("alice");

            List<UserSubscription> result = userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(user);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("existsByUserAndChannel()")
    class ExistsByUserAndChannel {

        @Test
        @DisplayName("正常系：購読していればtrueを返す")
        void testMethod01() {
            AppUser user = persistUser("alice");
            MonitoredChannel channel = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            userSubscriptionRepository.save(UserSubscription.builder().user(user).channel(channel).build());

            boolean exists = userSubscriptionRepository.existsByUserAndChannel(user, channel);

            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("正常系：購読していなければfalseを返す")
        void testMethod02() {
            AppUser user = persistUser("alice");
            MonitoredChannel channel = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");

            boolean exists = userSubscriptionRepository.existsByUserAndChannel(user, channel);

            assertThat(exists).isFalse();
        }
    }

    @Nested
    @DisplayName("deleteByUserAndChannel()")
    class DeleteByUserAndChannel {

        @Test
        @DisplayName("正常系：指定した組み合わせの購読だけを削除し、チャンネル本体・他の購読は残る")
        void testMethod01() {
            AppUser alice = persistUser("alice");
            AppUser bob = persistUser("bob");
            MonitoredChannel channel = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            userSubscriptionRepository.save(UserSubscription.builder().user(alice).channel(channel).build());
            userSubscriptionRepository.save(UserSubscription.builder().user(bob).channel(channel).build());

            int deletedCount = userSubscriptionRepository.deleteByUserAndChannel(alice, channel);
            entityManager.flush();
            entityManager.clear();

            assertThat(deletedCount).isEqualTo(1);
            assertThat(userSubscriptionRepository.existsByUserAndChannel(alice, channel)).isFalse();
            assertThat(userSubscriptionRepository.existsByUserAndChannel(bob, channel)).isTrue();
            assertThat(monitoredChannelRepository.findById(channel.getId())).isPresent();
        }

        @Test
        @DisplayName("正常系：購読していない組み合わせを指定しても例外にならず0件を返す")
        void testMethod02() {
            AppUser user = persistUser("alice");
            MonitoredChannel channel = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");

            int deletedCount = userSubscriptionRepository.deleteByUserAndChannel(user, channel);

            assertThat(deletedCount).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("連鎖削除")
    class CascadeDelete {

        @Test
        @DisplayName("正常系：利用者を削除すると、その利用者の購読も削除される")
        void testMethod01() {
            AppUser user = persistUser("alice");
            MonitoredChannel channel = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            UserSubscription subscription = userSubscriptionRepository.save(
                    UserSubscription.builder().user(user).channel(channel).build());
            Long subscriptionId = subscription.getId();

            appUserRepository.delete(user);
            entityManager.flush();
            entityManager.clear();

            assertThat(userSubscriptionRepository.existsById(subscriptionId)).isFalse();
            assertThat(monitoredChannelRepository.findById(channel.getId())).isPresent();
        }

        @Test
        @DisplayName("正常系：チャンネルを削除すると、そのチャンネルへの購読も削除される")
        void testMethod02() {
            AppUser user = persistUser("alice");
            MonitoredChannel channel = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            UserSubscription subscription = userSubscriptionRepository.save(
                    UserSubscription.builder().user(user).channel(channel).build());
            Long subscriptionId = subscription.getId();

            monitoredChannelRepository.delete(channel);
            entityManager.flush();
            entityManager.clear();

            assertThat(userSubscriptionRepository.existsById(subscriptionId)).isFalse();
            assertThat(appUserRepository.findById(user.getId())).isPresent();
        }
    }
}
