package com.example.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 利用者へ配信開始を通知した（しようとした）記録。利用者と配信の組ごとに 1 行。
 *
 * <p>全体向けの通知は「通知済みの動画 ID」をチャンネルに 1 つ持てば足りるが、利用者向けは
 * 同じ配信を複数人へ送るため、<b>誰に送れたか</b>を組ごとに持つ必要がある。これが無いと、
 * 誰か 1 人への送信に失敗して次の巡回で送り直すとき、送れていた人にも重ねて送ってしまう。
 *
 * <h2>{@code (user_id, video_id)} の一意制約</h2>
 * 同じ組の行が 2 つあると、どちらを見て「送った」とするか決められなくなる。
 * アプリ側は「無ければ作る」で 1 行に保つが、判定漏れがあっても壊れないよう DB でも強制する。
 *
 * <h2>{@link OnDelete} による連鎖削除</h2>
 * 利用者の削除（一括 DELETE）が外部キーで失敗しないよう、利用者と一緒に消す
 * （{@link RecordingMark} と同じ）。チャンネルには紐づけていない。重複を防ぐには動画 ID で足りるため。
 */
@Entity
@Table(name = "user_notifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_notifications_user_id_video_id",
                columnNames = {"user_id", "video_id"}))
@Getter
@NoArgsConstructor
public class UserNotification {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 通知の相手。利用者を削除すると DB 側で一緒に消える。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    /**
     * 通知する配信の動画 ID（Twitch では配信 ID）。
     *
     * <p>列名を明示しているのは、一意制約の {@code columnNames} と確実に一致させるため。
     */
    @Column(name = "video_id", nullable = false)
    private String videoId;

    /**
     * 届けられなかった試行の回数。Webhook へ送って失敗した回と、配信の詳細が取れずに送らなかった回の両方を数える。上限に達した配信へはもう送らない（{@code UserNotificationService} 参照）。
     *
     * <p>新しいテーブルなので今は当たらないが、既存行のある状態で定義を変えても
     * ALTER が失敗しないよう DB 側の既定値を明示している（{@code docs/pitfalls.md} 参照）。
     */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int failureCount;

    /** 送信に成功した時刻。まだ届けられていなければ {@code null}。 */
    private LocalDateTime notifiedAt;

    /**
     * 最後に送信に失敗した時刻。失敗していなければ {@code null}。
     * Webhook へ送って失敗したときだけ付け、配信の詳細が取れずに送らなかった回には付けない（利用者の Webhook の不具合ではないのに、通知の設定画面に警告が出てしまうため）。
     *
     * <p>失敗回数だけでは「今も届いていない」のか「昔 1 度失敗しただけ」なのか分からず、
     * Discord 側で Webhook を消した利用者が毎回黙って失敗し続けても気付けないため、時刻を残す。
     * Webhook を登録し直すと消す（前の Webhook の失敗を今の失敗と見せないため。
     * {@code UserNotificationRepository#clearFailures} 参照）。
     * NULL を許すので、既存の行がある DB でも ALTER が失敗しない（{@code docs/pitfalls.md} 参照）。
     */
    private LocalDateTime lastFailedAt;

    /**
     * 最後に送信に失敗した理由（200 文字で切ったもの）。失敗していなければ {@code null}。
     *
     * <p>WARN のログは古いものから消えるため、利用者から「届かない」と言われたときに理由を DB で引けるようにする。
     * 長さを切るのは呼び出し側（例外のメッセージには Discord の応答本文が入り、長さが決まらないため）。
     */
    @Column(length = 200)
    private String lastError;

    /**
     * まだ送っていない組の行を作る。
     *
     * @param user    通知の相手
     * @param videoId 通知する配信の動画 ID
     */
    public UserNotification(AppUser user, String videoId) {
        this.user = user;
        this.videoId = videoId;
    }
}
