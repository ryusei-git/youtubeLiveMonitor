package com.example.monitor.dto;

import com.example.monitor.entity.AuditLog;

import java.time.LocalDateTime;

/**
 * 監査ログ1件を API のレスポンスとして返す形。
 *
 * <p>エンティティを API に直接返さない方針（{@code AGENTS.md} 参照）に従い、
 * 閲覧画面が必要とする項目だけを詰め替えて返す。
 *
 * @param id         監査ログの主キー
 * @param occurredAt 操作が発生した時刻
 * @param requestId  相関ID。相関ID導入前の経路など、取得できない場合は {@code null}
 * @param userId     操作を行った利用者の主キー。未ログインでの操作では {@code null}
 * @param username   操作を行った利用者名。未ログインでの操作では {@code null}
 * @param clientIp   操作元のクライアントIP
 * @param action     操作の種別
 * @param targetType 操作対象の種類。対象が無い操作では {@code null}
 * @param targetId   操作対象の識別子。対象が無い操作では {@code null}
 * @param outcome    操作の結果
 * @param detail     補足の説明。無ければ {@code null}
 */
public record AuditLogResponse(
        Long id,
        LocalDateTime occurredAt,
        String requestId,
        Long userId,
        String username,
        String clientIp,
        String action,
        String targetType,
        String targetId,
        String outcome,
        String detail
) {

    /**
     * エンティティからレスポンスを組み立てる。
     *
     * @param auditLog 変換元のエンティティ
     * @return 変換後のレスポンス
     */
    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getId(),
                auditLog.getOccurredAt(),
                auditLog.getRequestId(),
                auditLog.getUserId(),
                auditLog.getUsername(),
                auditLog.getClientIp(),
                auditLog.getAction().name(),
                auditLog.getTargetType(),
                auditLog.getTargetId(),
                auditLog.getOutcome().name(),
                auditLog.getDetail()
        );
    }
}
