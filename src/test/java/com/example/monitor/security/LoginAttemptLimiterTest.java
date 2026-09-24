package com.example.monitor.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoginAttemptLimiter")
class LoginAttemptLimiterTest {
    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Nested
    @DisplayName("begin()")
    class Begin {
        @Test
        @DisplayName("異常系：同じ利用者名は接続元を変えても5回の失敗で制限される")
        void testMethod01() {
            for (int index = 0; index < 5; index++) {
                fail("user", "198.51.100." + index);
            }

            LoginAttemptLimiter.Attempt blocked = limiter.begin("user", "198.51.100.99");
            assertThat(blocked.allowed()).isFalse();
            assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
            assertThat(limiter.begin("other", "198.51.100.99").allowed()).isTrue();
        }

        @Test
        @DisplayName("異常系：利用者名を変えても同じ接続元は20回の失敗で制限される")
        void testMethod02() {
            for (int index = 0; index < 20; index++) {
                fail("user-" + index, "198.51.100.1");
            }

            assertThat(limiter.begin("new-user", "198.51.100.1").allowed()).isFalse();
            assertThat(limiter.begin("new-user", "198.51.100.2").allowed()).isTrue();
        }

        @Test
        @DisplayName("正常系：制限中の拒否は期限を延ばさず15分後に解除される")
        void testMethod03() {
            for (int index = 0; index < 5; index++) fail("user", "198.51.100.1");

            clock.advance(Duration.ofMinutes(14).plusSeconds(59));
            assertThat(limiter.begin("user", "198.51.100.1").retryAfterSeconds()).isEqualTo(1);
            clock.advance(Duration.ofSeconds(1));
            assertThat(limiter.begin("user", "198.51.100.1").allowed()).isTrue();
        }

        @Test
        @DisplayName("異常系：認証中の5枠を予約し、完了前の並列送信を通さない")
        void testMethod04() {
            LoginAttemptLimiter.Attempt[] inFlight = new LoginAttemptLimiter.Attempt[5];
            for (int index = 0; index < inFlight.length; index++) {
                inFlight[index] = limiter.begin("user", "198.51.100.1");
                assertThat(inFlight[index].allowed()).isTrue();
            }
            assertThat(limiter.begin("user", "198.51.100.1").allowed()).isFalse();
            limiter.finish(inFlight[0], true);
            assertThat(limiter.begin("user", "198.51.100.1").allowed()).isTrue();
        }

        @Test
        @DisplayName("異常系：認証中の20枠は利用者名を変えても接続元単位で制限される")
        void testMethod05() {
            for (int index = 0; index < 20; index++) {
                assertThat(limiter.begin("user-" + index, "198.51.100.1").allowed()).isTrue();
            }
            assertThat(limiter.begin("next", "198.51.100.1").allowed()).isFalse();
        }

        @Test
        @DisplayName("異常系：容量上限では新しいキーを拒否し既存の制限を維持する")
        void testMethod06() {
            for (int index = 0; index < 10_000; index++) {
                fail("user-" + index, "address-" + index);
            }

            LoginAttemptLimiter.Attempt blocked = limiter.begin("new-user", "new-address");
            assertThat(blocked.allowed()).isFalse();
            assertThat(blocked.retryAfterSeconds()).isEqualTo(60);
            assertThat(limiter.begin("user-0", "address-0").allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("finish()")
    class Finish {
        @Test
        @DisplayName("正常系：成功すると利用者名と接続元の連続失敗が両方リセットされる")
        void testMethod01() {
            for (int index = 0; index < 4; index++) fail("user", "198.51.100.1");
            limiter.finish(limiter.begin("user", "198.51.100.1"), true);

            for (int index = 0; index < 4; index++) fail("user", "198.51.100.1");
            assertThat(limiter.begin("user", "198.51.100.1").allowed()).isTrue();
        }

        @Test
        @DisplayName("正常系：同じ認証枠の完了を重複通知しても失敗は二重計上されない")
        void testMethod02() {
            LoginAttemptLimiter.Attempt attempt = limiter.begin("user", "198.51.100.1");
            limiter.finish(attempt, false);
            limiter.finish(attempt, false);

            for (int index = 0; index < 3; index++) fail("user", "198.51.100.1");
            assertThat(limiter.begin("user", "198.51.100.1").allowed()).isTrue();
        }
    }

    private void fail(String username, String address) {
        LoginAttemptLimiter.Attempt attempt = limiter.begin(username, address);
        assertThat(attempt.allowed()).isTrue();
        limiter.finish(attempt, false);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-24T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }
}
