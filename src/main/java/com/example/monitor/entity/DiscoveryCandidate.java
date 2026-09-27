package com.example.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 新人発掘で見つけたチャンネルの候補と、友人の判定（Issue #488）。
 *
 * <h2>なぜ主キーが YouTube のチャンネル ID か</h2>
 * 同じチャンネルを何度見つけても 1 行にするため。「ちがう」と判定した行も残しておき、
 * 次の巡回で同じ人気チャンネルを候補に戻さないようにする（却下の記録が一番効く、という調査の結果）。
 *
 * <h2>30 日の決まり（YouTube API Services の規約 III.E.4.d）</h2>
 * API で取った値は 30 日以内に取り直すか消す。そのため {@link Status#REJECTED} にしたら
 * {@link #channelId}・{@link #status}・{@link #decidedBy}・{@link #decidedAt} 以外を消し（{@link #clearApiData()}）、
 * それ以外の行は毎日の見回りが取り直す（{@code DiscoveryService.sweep()}）。
 *
 * <p>NOT NULL の列には DB 側の既定値を書き、enum は文字列の列にする
 * （{@code docs/pitfalls.md}「既存データがある状態で NOT NULL の boolean カラムを追加すると失敗する」
 * 「enum の列挙子を増やすと既存 DB で全更新が失敗する」）。
 */
@Entity
@Table(name = "discovery_candidates")
@Getter
@Setter
@NoArgsConstructor
public class DiscoveryCandidate {

    /** 判定。{@code CANDIDATE} はまだ誰も判定していない。 */
    public enum Status { CANDIDATE, VTUBER, REJECTED }

    /** YouTube のチャンネル ID（{@code UC...}）。 */
    @Id
    @Column(length = 64)
    private String channelId;

    private String title;
    private String iconUrl;

    /** チャンネルの説明の先頭 500 字。VTuber らしさの語を探した元なので、画面で確かめられるよう持つ。 */
    @Column(length = 500)
    private String description;

    /** 登録者の数。非公開なら {@code null}。 */
    private Long subscriberCount;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean subscriberHidden;

    private Long videoCount;
    private Instant channelPublishedAt;
    private Instant firstUploadAt;
    private String sampleVideoId;
    private String sampleVideoTitle;

    /** 一致した語（カンマ区切り）。点数ではなく語そのものを出す（独自の指標を作らない、規約 III.E.4.h）。 */
    private String matchedWords;

    /** 見つけた検索語。手動の登録では {@code null}。 */
    private String foundByTerm;

    private Instant discoveredAt;
    private Instant refreshedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'CANDIDATE'")
    private Status status = Status.CANDIDATE;

    /** 判定した利用者のログイン ID。利用者を消しても判定は残したいので、外部キーにしない。 */
    private String decidedBy;
    private Instant decidedAt;

    /** 「ちがう」にしたとき、判定以外の API の値をすべて消す（30 日の決まり）。 */
    public void clearApiData() {
        title = null;
        iconUrl = null;
        description = null;
        subscriberCount = null;
        subscriberHidden = false;
        videoCount = null;
        channelPublishedAt = null;
        firstUploadAt = null;
        sampleVideoId = null;
        sampleVideoTitle = null;
        matchedWords = null;
        foundByTerm = null;
        discoveredAt = null;
        refreshedAt = null;
    }
}
