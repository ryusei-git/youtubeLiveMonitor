package com.example.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * 録画の中の「ここは耳キス」のような音の印。利用者が再生画面から付ける（#459）。
 *
 * <p>使い道は 2 つある。再生画面で「次の耳キスへ」飛ぶことと、あとで自動検出を学び直すときの正解にすること。
 * Java で書ける検出の方式では耳キスを見分けられなかった（#460）ため、まず人の付けた印をためる。
 *
 * <h2>区間ではなく 1 時点（ミリ秒）で持つ</h2>
 * 耳キスは一瞬の音で、始まりと終わりを付けさせても手間が増えるだけのため。
 * 区間が要る種類（耳舐めなど）を足すときに、終わりの列を足す。
 *
 * <h2>全員で共有し、付けた人を記録する</h2>
 * {@link RecordingMark}（視聴済み・お気に入り）と違い、印は録画の中身についての記録なので全員に見せる。
 * それでも付けた人を持つのは、消せる人を本人に限るためと、学び直しで人ごとの付け方の一致を見るため。
 *
 * <h2>{@link OnDelete} による連鎖削除</h2>
 * 録画が消えれば印の指す先が無くなり、利用者が消えれば印を消せる人がいなくなる。
 * 録画の削除（{@code deleteById}）や利用者の削除（一括 DELETE）が外部キーで失敗しないよう、
 * {@link RecordingMark} と同じく DB 側の {@code ON DELETE CASCADE} で一緒に消す。
 *
 * <p>{@code (recording_id, kind)} の索引は、再生画面が開くたびに引く「この録画のこの種類の印」の一覧のため。
 */
@Entity
@Table(name = "sound_marks",
        indexes = @Index(name = "idx_sound_marks_recording_id_kind", columnList = "recording_id, kind"))
@Getter
@NoArgsConstructor
public class SoundMark {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 印を付けた録画。録画を削除すると DB 側で一緒に消える。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recording_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Recording recording;

    /** 印を付けた利用者。利用者を削除すると DB 側で一緒に消える。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    /**
     * 印の種類。
     *
     * <p>{@code columnDefinition} で文字列の列にしているのは、無いと H2 のネイティブ ENUM 型で作られ、
     * 種類を足した瞬間にこの列の全読み書きが壊れるため（{@code docs/pitfalls.md}
     * 「enum の列挙子を増やすと既存 DB で全更新が失敗する」）。種類は今後増える前提。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private Kind kind;

    /** 録画の先頭からの位置（ミリ秒）。 */
    @Column(nullable = false)
    private long positionMs;

    /** 印を付けた時刻。 */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * 新しい印を作る。
     *
     * @param recording  録画
     * @param user       付けた利用者
     * @param kind       種類
     * @param positionMs 録画の先頭からの位置（ミリ秒）
     */
    public SoundMark(Recording recording, AppUser user, Kind kind, long positionMs) {
        this.recording = recording;
        this.user = user;
        this.kind = kind;
        this.positionMs = positionMs;
        this.createdAt = Instant.now();
    }

    /**
     * 印の種類。
     *
     * <p>耳舐め・耳はむ・吐息は、耳キスとまとめずに別の種類として足す（利用者の決定、#459）。
     */
    public enum Kind {
        /** 耳キス。唇の音（ちゅっ）だけでなく、声・囁きで言う「ちゅ」も含む（利用者の決定、#459）。 */
        EAR_KISS
    }
}
