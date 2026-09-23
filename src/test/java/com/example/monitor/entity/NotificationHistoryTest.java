package com.example.monitor.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationHistory")
class NotificationHistoryTest {

    @Nested
    @DisplayName("applyNotifiedAtOnInsert()")
    class ApplyNotifiedAtOnInsert {

        @Test
        @DisplayName("正常系：呼び出し時点の時刻がnotifiedAtに設定される")
        void testMethod01() {
            NotificationHistory history = new NotificationHistory();
            LocalDateTime before = LocalDateTime.now();

            history.applyNotifiedAtOnInsert();

            LocalDateTime after = LocalDateTime.now();
            assertThat(history.getNotifiedAt()).isBetween(before, after);
        }
    }
}
