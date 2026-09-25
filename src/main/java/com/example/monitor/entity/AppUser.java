package com.example.monitor.entity;

import com.example.monitor.util.DiscordWebhookUrl;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * このアプリにログインできる利用者。
 *
 * <p>テーブル名を {@code app_users} としているのは、{@code users} が H2 の予約語と
 * 衝突する可能性があるため（{@code docs/user-portal-design.md} 3.2 参照）。
 *
 * <p>{@link #passwordHash} には BCrypt でハッシュ化した値だけを保存する。平文のパスワードを
 * 保持するフィールドはこのクラスに存在しない。
 */
@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ログインに使う利用者名。 */
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt でハッシュ化したパスワード。平文は保存しない。 */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /**
     * この利用者の権限。
     *
     * <p>{@code columnDefinition} を明示しているのは、H2 のネイティブ ENUM 型を使わせないため。
     * これが無いと Hibernate は列を作成時点の値だけを許すネイティブ ENUM 型として作ってしまい、
     * 後から列挙子を増やした瞬間にその列の全読み書きが壊れる
     * （{@code Recording.status} で実際に発生した。{@code docs/pitfalls.md}「enum の列挙子を増やすと既存 DB で全更新が失敗する」参照）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private Role role;

    /**
     * 無効化された利用者かどうか。無効化してもレコードは削除しない（ログの参照先を残すため）。
     *
     * <p>{@code columnDefinition} でDB側の既定値を明示しているのは、この列を追加する時点で
     * 既に利用者が登録されている環境で {@code ddl-auto: update} が
     * {@code ALTER TABLE ... ADD COLUMN ... NOT NULL} を実行する際、既定値が無いと
     * 既存行に値を埋められず失敗するため（{@code MonitoredChannel.recordEnabled} と同じ理由。
     * こちらは逆に「既定で有効」にしたいので {@code default true} にしている）。
     */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean enabled = true;

    /** アカウントを作成した時刻。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 最後にログインに成功した時刻。まだ一度もログインしていなければ {@code null}。 */
    private LocalDateTime lastLoginAt;

    /**
     * 最後にパスワードを変えた時刻。一度も変えていなければ {@code null}。
     *
     * <p>ほかのセッションを失効させるために持つ（#321）。ログイン時の値をセッションの主体に持たせ、
     * これより前にログインしたセッションを {@code ActiveAppUserFilter} が次のリクエストで落とす。
     * パスワードが漏れた疑いで変えたのに、漏れた先のセッションが使い続けられるのを防ぐため。
     * {@code NULL} を許すのは、利用者が既にいる DB へ {@code ddl-auto: update} で足しても ALTER が失敗しないため。
     */
    private LocalDateTime passwordChangedAt;

    /**
     * 管理者が発行したパスワードの再設定用の token（#324）。発行していない・使い終わったら {@code null}。
     *
     * <p>別のテーブルにせず利用者に 1 枠だけ持たせるのは、新しく発行すれば上書きで前の token が
     * 使えなくなるため（漏れた古いリンクを取り消す手順が要らない）。使ったら消すので 1 回限りになる。
     * <b>知っていればパスワードを決め直せる秘密</b>なので、API の応答（発行した直後を除く）・ログに出さない。
     * {@code NULL} を許すのは、利用者が既にいる DB へ {@code ddl-auto: update} で足しても ALTER が失敗しないため。
     */
    @Column(unique = true, length = 64)
    private String passwordResetToken;

    /** {@link #passwordResetToken} の期限。これを過ぎた token は使えない。 */
    private LocalDateTime passwordResetExpiresAt;

    /**
     * 配信開始の通知を送る Discord の Webhook の URL。登録していなければ {@code null}（送らない）。
     *
     * <p>通知を使いたい人だけが登録する（#149）。{@code NULL} を許す列なので、利用者が既にいる DB へ
     * {@code ddl-auto: update} で足しても ALTER は失敗しない（NOT NULL の列を足すときの落とし穴に当たらない）。
     *
     * <p><b>秘密情報。</b>URL を知っていれば誰でもその Discord のチャンネルへ書き込めるため、
     * API では登録済みかどうかしか返さず、ログにも監査ログにも出さない。
     * 受け付けるのは Discord の Webhook の形だけ（{@link DiscordWebhookUrl}）。
     */
    @Column(length = DiscordWebhookUrl.MAX_LENGTH)
    private String discordWebhookUrl;

    /** 作成時刻を自動設定する。JPA が INSERT 直前に呼び出す。 */
    @PrePersist
    void applyCreatedAtOnInsert() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 新規作成用のコンストラクタ。
     *
     * @param username     ログインID
     * @param passwordHash BCrypt でハッシュ化済みのパスワード
     * @param role         権限
     */
    public AppUser(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    /** 利用者の権限。 */
    public enum Role {
        /** 管理者。DB管理・ログ閲覧・設定変更など既存の管理機能にアクセスできる。 */
        ADMIN,
        /** 一般利用者。購読したチャンネルの録画を見る画面（{@code /my} と {@code /api/my/**}）を使う。 */
        USER
    }
}
