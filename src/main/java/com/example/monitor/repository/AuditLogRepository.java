package com.example.monitor.repository;

import com.example.monitor.entity.AuditLog;
import org.springframework.data.repository.Repository;

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
 * <p>閲覧・絞り込み用の検索メソッドは、管理者向け閲覧画面を実装する段階2-5で追加する。
 * 現時点（段階2-1）では記録の受け皿を用意するだけなので {@code save} 以外は宣言していない。
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
}
