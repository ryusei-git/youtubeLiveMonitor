package com.example.monitor.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationOutcome")
class NotificationOutcomeTest {

    @Nested
    @DisplayName("success()")
    class Success {

        @Test
        @DisplayName("正常系：successfulがtrue、errorMessageがnullになる")
        void testMethod01() {
            NotificationOutcome outcome = NotificationOutcome.success();

            assertThat(outcome.successful()).isTrue();
            assertThat(outcome.errorMessage()).isNull();
        }
    }

    @Nested
    @DisplayName("failure()")
    class Failure {

        @Test
        @DisplayName("正常系：successfulがfalse、指定した理由がerrorMessageに設定される")
        void testMethod01() {
            NotificationOutcome outcome = NotificationOutcome.failure("Webhook URLが未設定です");

            assertThat(outcome.successful()).isFalse();
            assertThat(outcome.errorMessage()).isEqualTo("Webhook URLが未設定です");
        }
    }
}
