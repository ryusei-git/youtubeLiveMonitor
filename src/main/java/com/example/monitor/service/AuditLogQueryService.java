package com.example.monitor.service;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditLog;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 管理者向け監査ログ閲覧画面のための検索窓口。
 *
 * <p>{@link AuditLogger} とはクラスを分けている。{@link AuditLogger} は「記録の失敗を
 * 業務へ波及させない」という書き込み専用の責務に絞ったクラスであり（同クラスの JavaDoc 参照）、
 * 検索用のメソッドを足すとその責務が曖昧になるため。
 */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    private final AuditLogRepository auditLogRepository;

    /**
     * 条件を指定して監査ログを検索する。
     *
     * @param since     発生時刻の下限。絞り込まないなら {@code null}
     * @param until     発生時刻の上限。絞り込まないなら {@code null}
     * @param username  操作者名の完全一致。絞り込まないなら {@code null}
     * @param action    操作種別。絞り込まないなら {@code null}
     * @param outcome   操作結果。絞り込まないなら {@code null}
     * @param requestId 相関ID。絞り込まないなら {@code null}
     * @param pageable  ページ指定
     * @return 発生時刻の降順に並んだ検索結果
     */
    public Page<AuditLog> search(LocalDateTime since, LocalDateTime until, String username,
                                  AuditAction action, AuditOutcome outcome, String requestId,
                                  Pageable pageable) {
        return auditLogRepository.search(since, until, username, action, outcome, requestId, pageable);
    }
}
