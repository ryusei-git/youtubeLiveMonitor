package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AppUser.Role;
import com.example.monitor.entity.MonitoredChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * Discord の Webhook の URL だけを更新する。登録・変更・解除（{@code null}）に使う。
     *
     * <p>{@link #updateLastLoginAt} と同じく、読み込んだエンティティを {@code save} しないのは、
     * その間に管理操作（無効化など）で変わった他の列を古い値で書き戻さないため。
     *
     * @param id  利用者の主キー
     * @param url 登録する URL。解除するなら {@code null}
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.discordWebhookUrl = :url WHERE u.id = :id")
    int updateDiscordWebhookUrl(@Param("id") Long id, @Param("url") String url);

    /**
     * このチャンネルを購読していて、Discord の Webhook を登録している有効な利用者を返す。
     * 配信開始を利用者ごとに通知する相手を決めるのに使う（巡回から呼ばれる）。
     *
     * <p>購読の行ではなく利用者そのものを返すのは、巡回がトランザクションの外で動くため。
     * 購読の {@code user} は遅延読み込みなので、購読を返すと利用者の項目を読んだ時点で失敗する。
     * 無効化された利用者には送らない。
     *
     * @param channel 配信が始まったチャンネル
     * @return 通知の相手。いなければ空
     */
    @Query("SELECT s.user FROM UserSubscription s WHERE s.channel = :channel "
            + "AND s.user.enabled = true AND s.user.discordWebhookUrl IS NOT NULL")
    List<AppUser> findNotificationTargets(@Param("channel") MonitoredChannel channel);

    /**
     * セッションに残った認証情報だけでは無効化・削除・パスワードの変更を検出できないため、毎回現状を照合する。
     *
     * <p>パスワードの変更は、DB の変更時刻がセッションの値より後なら失効とする。セッションの値が
     * {@code null}（変更前にログインした）で DB に値があれば、SQL の比較が偽になって失効する。
     * 等号でなく {@code <=} なのは、DB の時刻の精度で丸められても変更した本人のセッションを落とさないため。
     *
     * @param id                ログイン時の利用者ID
     * @param passwordChangedAt ログイン時点の最後のパスワード変更の時刻。変えていなければ {@code null}
     * @return 現在も存在し有効で、その後パスワードが変わっていなければ true
     */
    @Query("SELECT COUNT(u) > 0 FROM AppUser u WHERE u.id = :id AND u.enabled = true "
            + "AND (u.passwordChangedAt IS NULL OR u.passwordChangedAt <= :passwordChangedAt)")
    boolean isSessionValid(@Param("id") Long id, @Param("passwordChangedAt") LocalDateTime passwordChangedAt);

    /**
     * パスワードのハッシュと変更時刻だけを更新する。
     *
     * <p>変更時刻を同時に書くのは、これより前にログインしたセッションを失効させるため（{@link #isSessionValid}）。
     * 読み込んだエンティティを {@code save} しないのは {@link #updateLastLoginAt} と同じ理由。
     *
     * @param id           利用者の主キー
     * @param passwordHash エンコード済みの新しいパスワード
     * @param changedAt    変更した時刻
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.passwordHash = :passwordHash, u.passwordChangedAt = :changedAt WHERE u.id = :id")
    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash,
                       @Param("changedAt") LocalDateTime changedAt);

    /**
     * パスワードの再設定用の token から利用者を引く。再設定の画面を開いた時点の確認に使う。
     *
     * @param token 再設定用のリンクに載っていた文字列
     * @return 該当する利用者。使用済み・上書き済み・存在しない token なら {@link Optional#empty()}
     */
    Optional<AppUser> findByPasswordResetToken(String token);

    /**
     * パスワードの再設定用の token を書き込む。前の token は上書きで使えなくなる。
     *
     * <p>{@link #disableUser} と同じく、読み込み後に権限が変わっても管理者に発行しないよう更新条件にも権限を含める。
     *
     * @param id        利用者の主キー
     * @param role      操作可能な権限
     * @param token     新しい token
     * @param expiresAt 期限
     * @return 更新した件数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AppUser u SET u.passwordResetToken = :token, u.passwordResetExpiresAt = :expiresAt "
            + "WHERE u.id = :id AND u.role = :role")
    int updatePasswordResetToken(@Param("id") Long id, @Param("role") Role role,
                                 @Param("token") String token, @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * 再設定用の token を使ってパスワードを書き換え、同時に token を消す。
     *
     * <p>token と期限を更新条件に入れた 1 本の UPDATE にしているのは、同じリンクが同時に 2 回送られても
     * 1 回しか通さないため（読んでから書く 2 段にすると、両方が「未使用」を読んで両方通りうる）。
     * 変更時刻を書くのは {@link #updatePassword} と同じく、それより前のセッションを失効させるため。
     *
     * @param token        再設定用のリンクに載っていた文字列
     * @param passwordHash エンコード済みの新しいパスワード
     * @param changedAt    変更した時刻
     * @param now          期限の判定の基準時刻
     * @return 更新した件数。token が使えなければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.passwordHash = :passwordHash, u.passwordChangedAt = :changedAt, "
            + "u.passwordResetToken = NULL, u.passwordResetExpiresAt = NULL "
            + "WHERE u.passwordResetToken = :token AND u.passwordResetExpiresAt > :now")
    int resetPasswordByToken(@Param("token") String token, @Param("passwordHash") String passwordHash,
                             @Param("changedAt") LocalDateTime changedAt, @Param("now") LocalDateTime now);

    /**
     * 読み込み後に権限が変わっても管理者を無効化しないよう、更新条件にも権限を含める。
     * @param id 利用者ID
     * @param role 操作可能な権限
     * @return 更新件数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AppUser u SET u.enabled = false WHERE u.id = :id AND u.role = :role")
    int disableUser(@Param("id") Long id, @Param("role") Role role);

    /**
     * 管理者保護をDBの削除条件でも保証し、購読だけを外部キーで連鎖削除する。
     * @param id 利用者ID
     * @param role 操作可能な権限
     * @return 削除件数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM AppUser u WHERE u.id = :id AND u.role = :role")
    int deleteUser(@Param("id") Long id, @Param("role") Role role);
}
