package com.example.monitor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

/**
 * 収集した外部動画（YouTube・Twitch の配信と投稿）の視聴先 1 件。
 *
 * <p>録画の有無に依存せず視聴先を残す。動画本体は保存しない。
 */
@Entity
@Table(name = "online_videos", indexes = @Index(columnList = "published_at"))
@Getter @Setter @NoArgsConstructor
public class OnlineVideo {
    /** 配信予定（待機所）。配信が始まると種類が変わるため、収集のたびに判定し直す。 */
    public static final String KIND_UPCOMING = "UPCOMING";
    /** 配信中・配信アーカイブ。 */
    public static final String KIND_STREAM = "STREAM";
    /** 通常の投稿動画・ショート。 */
    public static final String KIND_UPLOAD = "UPLOAD";
    /**
     * 消えた待機所（削除された・存在しない）。待機所と判定済みの動画で、動画ページが「再生できません」
     * （{@code playabilityStatus} が {@code ERROR}）を返したもの。
     * 待機所が削除されると、{@link #KIND_UPCOMING} のまま「配信中・配信予定」の段に残り、収集のたびに動画ページを
     * 取り直し続けるため区別する。どの段にも出さず、種類の判定し直しもしない
     * （あとで配信が始まったら、巡回の {@code OnlineVideoService.observe} が {@link #KIND_STREAM} に移す）。
     */
    public static final String KIND_MISSING = "MISSING";

    @Id @Column(length = 100)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MonitoredChannel channel;
    @Column(length = 2048, nullable = false)
    private String watchUrl;
    @Column(length = 2048)
    private String liveWatchUrl;
    @Column(length = 2048)
    private String thumbnailUrl;
    /** 既存行にも 0 を補うため、NOT NULL カラムの追加時に DB 側の既定値を持たせる。 */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int thumbnailAttempts;
    /** プロセス再起動後も待機間隔を維持し、同じ失敗を毎巡回で繰り返さない。 */
    private Instant thumbnailNextAttemptAt;
    @Column(length = 1024, nullable = false)
    private String title;
    private Instant publishedAt;
    private Instant discoveredAt;
    private Instant lastObservedAt;
    @Column(columnDefinition = "boolean default false")
    private boolean live;
    /**
     * 動画の種類（{@link #KIND_UPCOMING} / {@link #KIND_STREAM} / {@link #KIND_UPLOAD} / {@link #KIND_MISSING}）。
     * null は「まだ判定できていない」で、投稿動画とはみなさない（次の収集で判定し直す）。
     * enum の {@code @Enumerated} にしないのは、H2 のネイティブ ENUM 型で作られると
     * 種類を増やしたときに既存 DB の全更新が失敗するため（docs/pitfalls.md 参照）。
     */
    @Column(length = 20)
    private String contentKind;
    /** 配信予定の開始時刻。配信予定以外では null（予定が過ぎた・始まった枠の古い時刻を残さない）。 */
    private Instant scheduledStartTime;
}
