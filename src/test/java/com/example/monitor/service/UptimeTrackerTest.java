package com.example.monitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UptimeTracker")
class UptimeTrackerTest {

    @Nested
    @DisplayName("getStartedAt()")
    class GetStartedAt {

        @Test
        @DisplayName("正常系：インスタンス生成時刻を返す")
        void testMethod01() {
            LocalDateTime before = LocalDateTime.now();

            UptimeTracker tracker = new UptimeTracker();

            LocalDateTime after = LocalDateTime.now();
            assertThat(tracker.getStartedAt()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("getUptimeSeconds()")
    class GetUptimeSeconds {

        @Test
        @DisplayName("正常系：生成直後は0秒以上の値を返す")
        void testMethod01() {
            UptimeTracker tracker = new UptimeTracker();

            long uptimeSeconds = tracker.getUptimeSeconds();

            assertThat(uptimeSeconds).isGreaterThanOrEqualTo(0L);
        }
    }
}
