package com.example.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * Discord への通知を試みた記録。１回の試行につき１件を残す。
 *
 * <p>成功したものだけでなく<b>失敗した試行も記録する</b>。
 * 「配信していたはずなのに通知が来なかった」ときに、
 * そもそも検知できていなかったのか、検知したが送信に失敗したのかを切り分けるため。
 * 失敗理由は {@link #errorMessage} に残る。
 *
 * @see MonitoredChannel
 */
@Entity
@Table(name = "notification_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationHistory {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 通知対象のチャンネル。
     *
     * <p>{@link OnDelete} により、チャンネルを削除すると紐づく履歴も DB 側で連鎖削除される。
     * これがないと履歴が残っているチャンネルを削除できず、参照整合性違反になる。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MonitoredChannel channel;

    /** 通知対象となった配信の動画 ID。 */
    @Column(nullable = false)
    private String videoId;

    /** 通知時点での配信タイトル。配信中に変更されることがあるため、あくまで通知時点のスナップショット。 */
    private String videoTitle;

    /**
     * 送信結果。
     *
     * <p>{@code columnDefinition} で文字列の列にしているのは、無いと H2 のネイティブ ENUM 型で作られ、
     * 列挙子を足した時点でこの列の読み書きがすべて失敗するため（docs/pitfalls.md 参照）。
     * 既に ENUM で作られた DB は、起動時の {@code ddl-auto: update}（Hibernate 7）がこの定義との食い違いを見て
     * {@code ALTER COLUMN ... SET DATA TYPE varchar(16)} を流し、値を保ったまま直す（#206 で本番 DB の複製で確認）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private NotificationResultType status;

    /** 送信に失敗した場合の例外メッセージ。成功時は {@code null}。 */
    private String errorMessage;

    /** 送信を試みた時刻。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime notifiedAt;

    /** 試行時刻を自動設定する。JPA が INSERT 直前に呼び出す。 */
    @PrePersist
    void applyNotifiedAtOnInsert() {
        this.notifiedAt = LocalDateTime.now();
    }

    /** 通知の送信結果。 */
    public enum NotificationResultType {
        /** Discord への送信が完了した。 */
        SUCCESS,
        /** Discord への送信に失敗した。詳細は {@link NotificationHistory#errorMessage} を参照。 */
        FAILED
    }
}
