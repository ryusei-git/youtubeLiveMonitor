package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EpochTimeConverter")
class EpochTimeConverterTest {

    @Nested
    @DisplayName("toSystemLocalDateTime()")
    class ToSystemLocalDateTime {

        @Test
        @DisplayName("正常系：エポックミリ秒をシステムのタイムゾーンの日時に変換する")
        void testMethod01() {
            long epochMillis = 1_700_000_000_000L;

            LocalDateTime result = EpochTimeConverter.toSystemLocalDateTime(epochMillis);

            LocalDateTime expected = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("正常系：エポック秒0（1970-01-01T00:00:00Z）を変換できる")
        void testMethod02() {
            LocalDateTime result = EpochTimeConverter.toSystemLocalDateTime(0L);

            LocalDateTime expected = LocalDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault());
            assertThat(result).isEqualTo(expected);
        }
    }
}
