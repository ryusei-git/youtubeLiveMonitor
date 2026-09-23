package com.example.monitor.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditLog")
class AuditLogTest {

    @Nested
    @DisplayName("builder()")
    class BuilderTest {

        @Test
        @DisplayName("正常系：全項目を指定して組み立てられる")
        void testMethod01() {
            AuditLog auditLog = AuditLog.builder()
                    .requestId("11111111-1111-1111-1111-111111111111")
                    .userId(1L)
                    .username("admin")
                    .clientIp("192.168.1.10")
                    .action(AuditAction.LOGIN_SUCCESS)
                    .targetType("CHANNEL")
                    .targetId("UCxxxxxxxx")
                    .outcome(AuditOutcome.SUCCESS)
                    .detail("備考")
                    .build();

            assertThat(auditLog.getRequestId()).isEqualTo("11111111-1111-1111-1111-111111111111");
            assertThat(auditLog.getUserId()).isEqualTo(1L);
            assertThat(auditLog.getUsername()).isEqualTo("admin");
            assertThat(auditLog.getClientIp()).isEqualTo("192.168.1.10");
            assertThat(auditLog.getAction()).isEqualTo(AuditAction.LOGIN_SUCCESS);
            assertThat(auditLog.getTargetType()).isEqualTo("CHANNEL");
            assertThat(auditLog.getTargetId()).isEqualTo("UCxxxxxxxx");
            assertThat(auditLog.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(auditLog.getDetail()).isEqualTo("備考");
        }

        @Test
        @DisplayName("正常系：未ログインの操作を想定し、利用者に関する項目を省略してもnullのまま組み立てられる")
        void testMethod02() {
            AuditLog auditLog = AuditLog.builder()
                    .action(AuditAction.LOGIN_FAILURE)
                    .outcome(AuditOutcome.FAILURE)
                    .detail("パスワード不一致")
                    .build();

            assertThat(auditLog.getUserId()).isNull();
            assertThat(auditLog.getUsername()).isNull();
            assertThat(auditLog.getTargetType()).isNull();
            assertThat(auditLog.getTargetId()).isNull();
            assertThat(auditLog.getRequestId()).isNull();
        }
    }

    @Nested
    @DisplayName("applyOccurredAtOnInsert()")
    class ApplyOccurredAtOnInsert {

        @Test
        @DisplayName("正常系：呼び出し時点の時刻がoccurredAtに設定される")
        void testMethod01() {
            AuditLog auditLog = new AuditLog();
            LocalDateTime before = LocalDateTime.now();

            auditLog.applyOccurredAtOnInsert();

            LocalDateTime after = LocalDateTime.now();
            assertThat(auditLog.getOccurredAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("正常系：builderで指定した時刻があっても呼び出し時点の時刻で上書きされる")
        void testMethod02() {
            AuditLog auditLog = AuditLog.builder()
                    .occurredAt(LocalDateTime.of(2000, 1, 1, 0, 0))
                    .action(AuditAction.LOGOUT)
                    .outcome(AuditOutcome.SUCCESS)
                    .build();
            LocalDateTime before = LocalDateTime.now();

            auditLog.applyOccurredAtOnInsert();

            LocalDateTime after = LocalDateTime.now();
            assertThat(auditLog.getOccurredAt()).isBetween(before, after);
        }
    }
}
