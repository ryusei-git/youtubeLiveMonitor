package com.example.monitor.repository;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditLog;
import com.example.monitor.entity.AuditOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 監査ログの永続化を担当するリポジトリ。
 *
 * <p><b>{@link org.springframework.data.jpa.repository.JpaRepository} ではなく、
 * Spring Data の空のマーカーインターフェースである {@link Repository} を直接継承している。</b>
 * {@code JpaRepository} を継承すると {@code delete}/{@code deleteById}/{@code deleteAll} や
 * 全カラム書き戻しの {@code save(既存id付きentity)} による上書きが自動的に使えるようになってしまう。
 * 「監査ログは追記専用で、リポジトリに更新・削除系のメソッドを定義しない」という方針
 * （{@code docs/user-portal-design.md} 1.4, 4.1 参照。書き換えられる証跡は証跡ではない）を
 * インターフェースの型だけで保証するため、{@link Repository} を直接継承し、
 * 必要なメソッド（{@link #save(AuditLog)}）だけを自分で宣言する
 * （Spring Data が標準で示している「公開するメソッドを絞り込む」手法）。
 *
 * <p>{@link #save(AuditLog)} は独自実装を書く必要はない。宣言したメソッドのシグネチャが
 * Spring Data JPA の既定実装（{@code SimpleJpaRepository}）のメソッドと一致するため、
 * 実行時に自動的にそちらへ委譲される。
 *
 * <p><b>検索メソッド（{@link #search}）は {@code JpaRepository} や
 * {@code JpaSpecificationExecutor} を継承せず、{@code @Query} を添えた素のメソッド宣言として
 * 追加している。</b>{@code JpaSpecificationExecutor} は {@code delete(Specification)} を
 * 持つため、検索のために継承すると上記の「更新・削除系のメソッドを持たない」という
 * 保証がインターフェースの型から崩れてしまう。
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    /**
     * 監査ログを1件追加する。
     *
     * <p>{@link AuditLog#id} は {@code IDENTITY} 戦略で INSERT 時に採番されるため、
     * 呼び出し側が主キーを指定して既存行を意図せず上書きしてしまうことはない
     * （常に新規追加になる）。
     *
     * @param auditLog 追加する監査ログ
     * @return 採番された {@code id} を含む、保存後のエンティティ
     */
    AuditLog save(AuditLog auditLog);

    /**
     * 条件を指定して監査ログを検索する。
     *
     * <p>絞り込みは DB 側で行う。1 ページ分だけ取得してからアプリ側で絞ると、
     * 条件に合う行がページの外にあったときに一覧から丸ごと消えてしまうため
     * （{@code NotificationHistoryService#searchHistory} と同じ理由）。
     * 各条件は {@code null} なら絞り込まない。
     *
     * @param since     発生時刻の下限
     * @param until     発生時刻の上限
     * @param username  操作者名の完全一致
     * @param action    操作種別
     * @param outcome   操作結果
     * @param requestId 相関ID
     * @param pageable  ページ指定
     * @return 発生時刻の降順に並んだ検索結果
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE (:since IS NULL OR a.occurredAt >= :since)
              AND (:until IS NULL OR a.occurredAt <= :until)
              AND (:username IS NULL OR a.username = :username)
              AND (:action IS NULL OR a.action = :action)
              AND (:outcome IS NULL OR a.outcome = :outcome)
              AND (:requestId IS NULL OR a.requestId = :requestId)
            ORDER BY a.occurredAt DESC
            """)
    Page<AuditLog> search(@Param("since") LocalDateTime since,
                           @Param("until") LocalDateTime until,
                           @Param("username") String username,
                           @Param("action") AuditAction action,
                           @Param("outcome") AuditOutcome outcome,
                           @Param("requestId") String requestId,
                           Pageable pageable);
}
