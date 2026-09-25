package com.example.monitor.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationDeliveryStatus")
class NotificationDeliveryStatusTest {

    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 9, 2, 0, 0);

    @Nested
    @DisplayName("failing()")
    class Failing {

        @Test
        @DisplayName("正常系：最後の失敗が最後の成功より新しければ true")
        void testMethod01() {
            assertThat(new NotificationDeliveryStatus(EARLIER, LATER).failing()).isTrue();
        }

        @Test
        @DisplayName("正常系：一度も届けていなくて失敗があれば true")
        void testMethod02() {
            assertThat(new NotificationDeliveryStatus(null, LATER).failing()).isTrue();
        }

        @Test
        @DisplayName("正常系：失敗の後に届けていれば、または失敗が無ければ false")
        void testMethod03() {
            assertThat(new NotificationDeliveryStatus(LATER, EARLIER).failing()).isFalse();
            assertThat(new NotificationDeliveryStatus(LATER, null).failing()).isFalse();
            assertThat(new NotificationDeliveryStatus(null, null).failing()).isFalse();
        }
    }
}
