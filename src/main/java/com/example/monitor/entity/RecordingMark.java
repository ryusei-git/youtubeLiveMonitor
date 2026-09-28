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
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * 利用者ごとの、録画に付けた「視聴済み」「お気に入り」の印と、再生位置。
 *
 * <p>{@link Recording} 側に列として持たせないのは、同じ録画を複数の利用者が見るため
 * （録画に 1 つの値を持たせると、誰かが視聴済みにした瞬間に全員の分が視聴済みになる）。
 * {@link UserSubscription} と同じく「利用者 × 対象」の中間テーブルにしている。
 *
 * <h2>{@code (user_id, recording_id)} の一意制約</h2>
 * 同じ組の行が 2 つあると、どちらの印が正しいのか決められなくなる。
 * アプリ側は「無ければ作る」で 1 行に保つが、判定漏れがあっても壊れないよう DB でも強制する。
 *
 * <h2>{@link OnDelete} による連鎖削除</h2>
 * 印は「その利用者がその録画をどう扱ったか」の記録に過ぎず、録画か利用者のどちらかが
 * 消えれば意味を失う。録画の削除（{@code deleteById}）や利用者の削除（一括 DELETE）が
 * 外部キーで失敗しないよう、DB 側の {@code ON DELETE CASCADE} で一緒に消す。
 *
 * <h2>{@link DynamicUpdate} で変わった列だけを書き戻す</h2>
 * 再生位置は再生中に 15 秒おきに書き込まれ、同じ行の視聴済み・お気に入りは利用者がボタンで変える。
 * Hibernate の既定の UPDATE は全列を書き戻すので、2 つの要求が重なると、先に読んだ古い値で
 * もう一方の変更（押したばかりのお気に入りなど）を黙って消す
 * （{@code docs/pitfalls.md}「監視ループから save(entity) を呼ばない」と同じ仕組み）。
 * 変わった列だけを UPDATE させて、別の列どうしの変更がぶつからないようにしている。
 */
@Entity
@DynamicUpdate
@Table(name = "recording_marks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_recording_marks_user_id_recording_id",
                columnNames = {"user_id", "recording_id"}))
@Getter
@Setter
@NoArgsConstructor
public class RecordingMark {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 印を付けた利用者。利用者を削除すると DB 側で一緒に消える。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    /** 印を付けた録画。録画を削除すると DB 側で一緒に消える。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recording_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Recording recording;

    /**
     * 視聴済みにした時刻。{@code null} なら未視聴。
     *
     * <p>真偽値ではなく時刻で持つのは、「最近見たもの」で並べたくなったときに
     * 列を足し直さずに済むため（真偽値は {@code watchedAt != null} で導ける）。
     */
    private Instant watchedAt;

    /**
     * お気に入りか。
     *
     * <p>新しいテーブルなので今は当たらないが、既存行のある状態で定義を変えても
     * ALTER が失敗しないよう DB 側の既定値を明示している（{@code docs/pitfalls.md} 参照）。
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean favorite;

    /**
     * 再生位置（秒）。{@code null} なら続きは無い（まだ位置を送っていない・最後まで見た）。
     *
     * <p>録画は配信 1 本まるごと（数時間）で、一度では見切らない。PC と iPhone を行き来しても続きから見られるよう、
     * ブラウザではなくここに持つ（ブラウザの保存領域は端末・ブラウザごとに分かれる）。
     *
     * <p>既存の行がある DB に足しても ALTER が失敗しないよう、NULL を許す型にしている
     * （{@code docs/pitfalls.md}「既存データがある状態で NOT NULL の boolean カラムを追加すると失敗する」）。
     */
    private Integer positionSeconds;

    /**
     * 再生位置を送った時刻。位置が無ければ {@code null}。
     *
     * <p>今は読まないが、「続きから見る」を最近見た順に並べるときに要る。後から足すと、それまでに送った位置の時刻が
     * 分からないため、位置と一緒に持っておく（{@link #watchedAt} を時刻で持つのと同じ考え方）。
     */
    private Instant positionUpdatedAt;

    /**
     * まだ印の無い組の行を作る。
     *
     * @param user      利用者
     * @param recording 録画
     */
    public RecordingMark(AppUser user, Recording recording) {
        this.user = user;
        this.recording = recording;
    }
}
