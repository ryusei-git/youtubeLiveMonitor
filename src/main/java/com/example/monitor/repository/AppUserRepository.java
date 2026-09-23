package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AppUser.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ログイン利用者の永続化を担当するリポジトリ。
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * ログインIDで利用者を検索する。{@code AppUserDetailsService} が認証のたびに使う。
     *
     * @param username ログインID
     * @return 見つかった利用者。未登録なら {@link Optional#empty()}
     */
    Optional<AppUser> findByUsername(String username);

    /**
     * 指定した権限の利用者が1人でも存在するかを判定する。
     *
     * <p>起動時の初期管理者作成で「管理者が1人もいなければ作る」の判定に使う
     * （{@code docs/user-portal-design.md} 3.3 参照）。特定のユーザー名との一致ではなく
     * 権限の有無で判定することで、運用者が初期管理者のユーザー名を後から変えていても
     * 二重作成しない。
     *
     * @param role 判定したい権限
     * @return 該当する利用者が1人でもいれば {@code true}
     */
    boolean existsByRole(Role role);

    /**
     * 最終ログイン時刻のみを更新する。
     *
     * <p>ログイン処理で読み込んだエンティティをそのまま {@code save} すると、その間に
     * 管理操作（無効化・権限変更など）で書き換えられた他のカラムを古い値で上書きしうる
     * （{@link MonitoredChannelRepository} が監視ループの更新に個別 UPDATE メソッドを
     * 使っているのと同じ理由）。
     *
     * @param id      利用者の主キー
     * @param loginAt ログイン時刻
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.lastLoginAt = :loginAt WHERE u.id = :id")
    int updateLastLoginAt(@Param("id") Long id, @Param("loginAt") LocalDateTime loginAt);
}
