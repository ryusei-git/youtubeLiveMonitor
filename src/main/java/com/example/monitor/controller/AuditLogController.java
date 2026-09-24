package com.example.monitor.controller;

import com.example.monitor.dto.AuditLogResponse;
import com.example.monitor.dto.PageResponse;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.service.AuditLogQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 管理者向け監査ログ閲覧画面のための REST API。
 *
 * <p>認可は {@code SecurityConfig} の {@code /api/audit-logs/**} が ADMIN 限定で担う
 * （このクラス自身は認可判定を持たない）。
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    /**
     * 条件を指定して監査ログを検索する。
     *
     * <p>条件を 1 つも指定しなければ、全件を発生時刻の降順で返す。
     *
     * @param since     発生時刻の下限
     * @param until     発生時刻の上限
     * @param username  操作者名の完全一致
     * @param action    操作種別
     * @param outcome   操作結果
     * @param requestId 相関ID
     * @param page      ページ番号（0 始まり）
     * @param size      1 ページあたりの件数
     * @return 発生時刻の降順に並んだ検索結果
     */
    @GetMapping
    public PageResponse<AuditLogResponse> search(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String requestId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (since != null && until != null && since.isAfter(until)) {
            throw new IllegalArgumentException("期間の開始は終了以前にしてください");
        }
        // 件数は呼び出し側の指定をそのまま信じない（極端な値で DB を引かせないため）
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));

        return PageResponse.from(auditLogQueryService
                .search(since, until, username, action, outcome, requestId, PageRequest.of(safePage, safeSize))
                .map(AuditLogResponse::from));
    }
}
