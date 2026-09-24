package com.example.monitor.service;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditLog;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.repository.AuditLogRepository;
import com.example.monitor.util.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 監査ログへの記録窓口。認証イベント・状態変更操作の両方から共通で使う。
 *
 * <h2>記録の失敗を呼び出し元へ伝播させない</h2>
 * 監査ログは証跡であって、業務そのものを止めるためのものではない。
 * DB 障害等で {@link AuditLogRepository#save} が失敗しても、<b>ログインや操作自体は
 * 通常どおり進める</b>。ここで例外を投げ返すと、監査ログの不調がそのまま
 * サービス停止に直結してしまう（記録できないことより、記録できないせいで
 * 誰もログインできなくなることのほうが実害が大きい）。失敗はアプリログに
 * WARN で残し、次の調査につなげる。
 *
 * <h2>相関IDはここで必ず埋める</h2>
 * 呼び出し側に {@link RequestContext} を意識させると、一部の呼び出し元だけ
 * 相関IDが抜ける事故が起きやすい。窓口をここ一箇所にまとめ、
 * {@link AuditLog#requestId} は常にこのクラスが設定する。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogger {

    private final AuditLogRepository repository;

    /**
     * 監査ログを1件記録する。
     *
     * @param action     操作の種別
     * @param outcome    操作の結果
     * @param userId     操作を行った利用者の主キー。未ログインでの操作（ログイン失敗など）では {@code null}
     * @param username   操作を行った利用者名。未ログインでの操作では {@code null}
     * @param clientIp   操作元のクライアントIP
     * @param targetType 操作対象の種類。対象が無い操作（ログイン等）では {@code null}
     * @param targetId   操作対象の識別子。対象が無い操作では {@code null}
     * @param detail     補足の説明。無ければ {@code null}
     */
    public void record(AuditAction action, AuditOutcome outcome, Long userId, String username,
                       String clientIp, String targetType, String targetId, String detail) {
        try {
            repository.save(AuditLog.builder()
                    .requestId(RequestContext.currentRequestId())
                    .userId(userId)
                    .username(username)
                    .clientIp(clientIp)
                    .action(action)
                    .outcome(outcome)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(detail)
                    .build());
        } catch (Exception e) {
            log.warn("監査ログの記録に失敗しました: action={}, outcome={}, user={}", action, outcome, username, e);
        }
    }

    /**
     * 対象を伴わない操作（ログイン・ログアウトなど）を記録する。
     *
     * @param action   操作の種別
     * @param outcome  操作の結果
     * @param userId   操作を行った利用者の主キー。未ログインでの操作では {@code null}
     * @param username 操作を行った利用者名。未ログインでの操作では {@code null}
     * @param clientIp 操作元のクライアントIP
     * @param detail   補足の説明。無ければ {@code null}
     */
    public void recordAuthEvent(AuditAction action, AuditOutcome outcome, Long userId, String username,
                                String clientIp, String detail) {
        record(action, outcome, userId, username, clientIp, null, null, detail);
    }
}
