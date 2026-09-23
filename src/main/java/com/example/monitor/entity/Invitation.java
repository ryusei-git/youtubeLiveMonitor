package com.example.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 管理者が発行する利用者登録の招待。
 *
 * <h2>なぜ招待制にするのか</h2>
 * 登録画面を誰でも開ける場所に置くと、リンクを知った第三者が勝手にアカウントを作れてしまう。
 * かといって管理者が利用者名とパスワードを決めて口頭で渡すのは、
 * <b>管理者が相手のパスワードを知っている</b>状態になり、使い回しの被害を広げうる。
 * 招待リンクなら、パスワードを決めるのは本人だけで済む。
 *
 * <h2>token は「知っていること」がそのまま権限になる</h2>
 * <b>これは秘密情報なので、ログにも画面のログにも出さないこと。</b>
 * 推測で当てられないよう、連番ではなく {@code SecureRandom} の 256 ビットから作る
 * （{@code InvitationService} 参照）。
 *
 * <p>さらに<b>1 回使ったら無効</b>（{@link #acceptedAt} が入る）、
 * かつ<b>期限付き</b>（{@link #expiresAt}）にしている。リンクは
 * チャットの履歴などに残り続けるため、漏れても被害が広がらないようにするため。
 */
@Entity
@Table(name = "invitations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Invitation {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 招待リンクに載せる秘密の文字列。
     *
     * <p>これを知っていること自体が登録の権限になるため、<b>ログに出さないこと</b>。
     */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /**
     * 誰に送ったかの覚え書き。管理者が一覧で見分けるためだけに使う。
     *
     * <p>招待される側には見せない（表示すると、相手の知らないところで付けた
     * あだ名がそのまま本人に見えてしまう）。
     */
    @Column(length = 100)
    private String label;

    /** 発行した時刻。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 期限。これを過ぎた招待は使えない。 */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** 使われた時刻。入っていれば使用済みで、二度目は受け付けない。 */
    private LocalDateTime acceptedAt;

    /** この招待で作られた利用者名。誰がどの招待を使ったかを後から追えるようにするため。 */
    @Column(length = 64)
    private String acceptedUsername;

    /** 発行時刻は必ずサーバー側で入れる（呼び出し側の指定に任せない）。 */
    @PrePersist
    void applyCreatedAtOnInsert() {
        createdAt = LocalDateTime.now();
    }

    /**
     * 今この招待を使えるかどうか。
     *
     * @param now 判定の基準時刻
     * @return 未使用かつ期限内なら {@code true}
     */
    public boolean isUsable(LocalDateTime now) {
        return acceptedAt == null && now.isBefore(expiresAt);
    }
}
