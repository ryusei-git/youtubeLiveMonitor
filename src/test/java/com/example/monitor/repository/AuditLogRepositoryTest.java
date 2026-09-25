package com.example.monitor.repository;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditLog;
import com.example.monitor.entity.AuditOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditLogRepository} が実際に永続化できること、および
 * 「更新・削除系のメソッドを持たない」というインターフェース設計そのものを検証する。
 *
 * <p>{@code JpaRepository} ではなく素の {@link org.springframework.data.repository.Repository}
 * を継承する構成は本プロジェクトで初めての例であり、宣言したメソッドが実際に
 * {@code SimpleJpaRepository} へ委譲されて動くかどうかは実装の詳細に依存するため、
 * 実際に Spring Data のリポジトリプロキシを生成する {@code @DataJpaTest} で確認する
 * （純粋な単体テストでは確認できない）。
 */
@DataJpaTest
@DisplayName("AuditLogRepository")
class AuditLogRepositoryTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("正常系：保存すると主キーが採番され、occurredAtが自動設定された状態で取得できる")
        void testMethod01() {
            AuditLog auditLog = AuditLog.builder()
                    .action(AuditAction.CHANNEL_REGISTER)
                    .outcome(AuditOutcome.SUCCESS)
                    .userId(1L)
                    .username("admin")
                    .targetType("CHANNEL")
                    .targetId("UCxxxxxxxx")
                    .build();

            AuditLog saved = auditLogRepository.save(auditLog);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getOccurredAt()).isNotNull();
            assertThat(saved.getAction()).isEqualTo(AuditAction.CHANNEL_REGISTER);
            assertThat(saved.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        }

        @Test
        @DisplayName("正常系：利用者に関する項目が無い操作（未ログイン時の失敗）も保存できる")
        void testMethod02() {
            AuditLog auditLog = AuditLog.builder()
                    .action(AuditAction.LOGIN_FAILURE)
                    .outcome(AuditOutcome.FAILURE)
                    .detail("パスワード不一致")
                    .build();

            AuditLog saved = auditLogRepository.save(auditLog);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getUserId()).isNull();
            assertThat(saved.getUsername()).isNull();
            assertThat(saved.getDetail()).isEqualTo("パスワード不一致");
        }
    }

    @Nested
    @DisplayName("インターフェース設計")
    class InterfaceContract {

        @Test
        @DisplayName("正常系：更新・削除系のメソッド（delete/deleteById/deleteAll）を1つも持たない")
        void testMethod01() {
            boolean hasDeleteOrUpdateMethod = Arrays.stream(AuditLogRepository.class.getMethods())
                    .map(Method::getName)
                    .anyMatch(name -> name.startsWith("delete") || name.equals("saveAndFlush"));

            assertThat(hasDeleteOrUpdateMethod)
                    .as("AuditLogRepositoryは追記専用であるべきで、削除系メソッドを公開してはならない")
                    .isFalse();
        }
    }
}
