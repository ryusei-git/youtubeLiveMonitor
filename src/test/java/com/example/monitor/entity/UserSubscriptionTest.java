package com.example.monitor.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserSubscription")
class UserSubscriptionTest {

    @Nested
    @DisplayName("applySubscribedAtOnInsert()")
    class ApplySubscribedAtOnInsert {

        @Test
        @DisplayName("正常系：呼び出し時点の時刻がsubscribedAtに設定される")
        void testMethod01() {
            UserSubscription subscription = new UserSubscription();
            LocalDateTime before = LocalDateTime.now();

            subscription.applySubscribedAtOnInsert();

            LocalDateTime after = LocalDateTime.now();
            assertThat(subscription.getSubscribedAt()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("builder()")
    class BuilderTest {

        @Test
        @DisplayName("正常系：利用者とチャンネルを指定して組み立てられる")
        void testMethod01() {
            AppUser user = new AppUser("alice", "hashed-password", AppUser.Role.USER);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxxxxxxxxxxxxxxxx", "Channel A");

            UserSubscription subscription = UserSubscription.builder()
                    .user(user)
                    .channel(channel)
                    .build();

            assertThat(subscription.getUser()).isSameAs(user);
            assertThat(subscription.getChannel()).isSameAs(channel);
        }
    }
}
