package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CaseInsensitiveMatcher")
class CaseInsensitiveMatcherTest {

    @Nested
    @DisplayName("findIgnoreCase()")
    class FindIgnoreCase {

        @Test
        @DisplayName("正常系：大文字小文字が完全一致する場合はその候補を返す")
        void testMethod01() {
            Optional<String> result = CaseInsensitiveMatcher.findIgnoreCase(List.of("CHANNELS", "NOTIFICATION_HISTORY"), "CHANNELS");

            assertThat(result).contains("CHANNELS");
        }

        @Test
        @DisplayName("正常系：大文字小文字が異なっても一致する候補を返す")
        void testMethod02() {
            Optional<String> result = CaseInsensitiveMatcher.findIgnoreCase(List.of("CHANNELS", "NOTIFICATION_HISTORY"), "channels");

            assertThat(result).contains("CHANNELS");
        }

        @Test
        @DisplayName("正常系：一致する候補が無い場合は空を返す")
        void testMethod03() {
            Optional<String> result = CaseInsensitiveMatcher.findIgnoreCase(List.of("CHANNELS"), "UNKNOWN_TABLE");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系：候補が空の場合は空を返す")
        void testMethod04() {
            Optional<String> result = CaseInsensitiveMatcher.findIgnoreCase(List.of(), "CHANNELS");

            assertThat(result).isEmpty();
        }
    }
}
