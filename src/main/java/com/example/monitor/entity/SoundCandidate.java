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
 * 検出器が録画に自動で付けた「耳キスの候補」（Issue #465・#469）。
 *
 * <h2>人の印（{@link SoundMark}）と別のテーブルにする理由</h2>
 * 人の印は、検出器を学び直すときの正解になる。検出器の結果は外れが多い前提（Issue #460 の調査で、
 * 耳キスだけを当てる精度は低かった）なので、同じテーブルに混ぜると正解が汚れる。
 *
 * <h2>候補ごとに「答え」を持つ理由</h2>
 * 聞いた人が「耳キス」「ちがう」と答えた結果は、学び直しの正例・負例になる（#460 でいちばんの壁だった
 * 「候補ごとの正解」）。答えは全員で共有し、最後の答えを有効にするので、候補の行に 1 つだけ持つ。
 *
 * <h2>検出器の版（{@link #detectorVersion}）を持つ理由</h2>
 * 学び直して重みを変えたら版を上げ、新しい版で付け直す。どの版の結果かが分からないと、答えを学び直しに
 * 使うときに、どのモデルが取り違えた音なのかを区別できない。
 *
 * <h2>版を上げても答えを引き継ぐ（Issue #481）</h2>
 * 画面と API は今の版の候補だけを出す。版を上げて付け直すと、答えた候補が「未確認」の新しい候補に置き換わって見え、
 * 聞き直させることになる。そこで付け直しのときに、ほかの版の答えのある候補を今の版へ写す（{@link #carryOver}）。
 * 元の行は消さない。その版の検出器が何を取り違えたかの記録で、学び直しの正解でもあるため。
 * 写した行にあとで答え直しても、変わるのは写した行だけで、元の行はそのまま残る。
 * 次に版を上げたときに写すのは写した行の方で、すでに写された元の行は写し元にしない（写しの鎖の末端だけを写す）。
 * 写した行の答えを取り消していれば、その位置は次の版へ何も写さない。元の行を写すと、取り消す前の答えが
 * 次の版でよみがえるため。
 *
 * <h2>{@link OnDelete} の使い分け</h2>
 * 録画が消えれば候補の指す先が無くなるので、{@link SoundMark} と同じく DB 側の {@code ON DELETE CASCADE} で
 * 一緒に消す。答えた人（{@link #reviewedBy}）は {@code ON DELETE SET NULL} にして、利用者を消しても答えは残す。
 * 答えは学び直しの正解で、答えた人が居なくなっても値打ちは変わらないため。
 *
 * <p>{@code (recording_id, kind, detector_version)} の索引は、付け直しと再生画面が引く
 * 「この録画のこの種類の、今の版の候補」の一覧のため。
 */
@Entity
@Table(name = "sound_candidates",
        indexes = @Index(name = "idx_sound_candidates_recording_id_kind_version",
                columnList = "recording_id, kind, detector_version"))
@Getter
@NoArgsConstructor
public class SoundCandidate {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 候補を付けた録画。録画を削除すると DB 側で一緒に消える。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recording_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Recording recording;

    /**
     * 候補の種類。{@link SoundMark} と同じ種類を使う（答えの確かめで人の印と並べるため）。
     *
     * <p>{@code columnDefinition} で文字列の列にする理由は {@link SoundMark} の {@code kind} と同じ
     * （{@code docs/pitfalls.md}「enum の列挙子を増やすと既存 DB で全更新が失敗する」）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private SoundMark.Kind kind;

    /** 録画の先頭からの位置（ミリ秒）。 */
    @Column(nullable = false)
    private long positionMs;

    /** 検出器の点数（耳キスらしさの確率。0〜1）。 */
    @Column(nullable = false)
    private double score;

    /** 候補を付けた検出器の版（重みのファイルの {@code version}）。 */
    @Column(nullable = false, length = 40)
    private String detectorVersion;

    /**
     * 聞いた人の答え。{@code null} ならまだ誰も答えていない。
     *
     * <p>文字列の列にする理由は {@link #kind} と同じ。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, columnDefinition = "varchar(16)")
    private Verdict verdict;

    /** 最後に答えた利用者。答えが無いとき・答えた人が削除されたときは {@code null}。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private AppUser reviewedBy;

    /** 最後に答えた時刻。答えが無いときは {@code null}。 */
    private Instant reviewedAt;

    /** 候補を付けた時刻。 */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * ほかの版から写した候補なら、写した元の候補の {@link #id}。検出器が付けた候補は {@code null}（Issue #481）。
     *
     * <p><b>学び直しで答えを数えるときは、ほかの行のこの列から指されていない行（写しの鎖の末端）のうち、
     * 答えのある行だけを数える。</b>写した行は元の行と同じ答えの写しなので、両方を数えると同じ答えを二重に数える。
     * また、写した行にあとで答え直したり取り消したりしていれば、利用者のいまの答えは写した行にしか無く、元の行は
     * 写した時点の答えのまま残っている（答えの API は今の版の候補しか書き換えない）。末端だけを数えれば、二重にならず、
     * 答え直しと取り消しも反映される。付け直しで次の版へ写す元も、同じ決まりで選ぶ
     * （{@code SoundCandidateRepository.findAnsweredInOtherVersions}）。
     *
     * <p>外部キーにしないのは、元の行は消さない決まりで（クラスの説明）、録画を消せば両方とも一緒に消えるため。
     * 既存の行は {@code null} のままでよいので、null を許す列にしている（NOT NULL の列は、既存の行がある DB への
     * 追加で失敗する。{@code docs/pitfalls.md}「既存データがある状態で NOT NULL の boolean カラムを追加すると失敗する」）。
     */
    private Long carriedFromId;

    /**
     * まだ答えの無い候補を作る。
     *
     * @param recording       録画
     * @param kind            種類
     * @param positionMs      録画の先頭からの位置（ミリ秒）
     * @param score           検出器の点数
     * @param detectorVersion 検出器の版
     */
    public SoundCandidate(Recording recording, SoundMark.Kind kind, long positionMs, double score,
                          String detectorVersion) {
        this.recording = recording;
        this.kind = kind;
        this.positionMs = positionMs;
        this.score = score;
        this.detectorVersion = detectorVersion;
        this.createdAt = Instant.now();
    }

    /**
     * ほかの版の答えのある候補を、今の版の候補として写す（クラスの説明「版を上げても答えを引き継ぐ」）。
     *
     * <p>点数は元の版の検出器のものをそのまま写す。今の版の検出器はその位置に候補を出していないこともあり、
     * 付け直す値が無いため。答えた人と時刻も写す。画面の「自分の答え」と、同じ位置の答えが
     * 複数の版にあるときに新しい方を選ぶ決まり（{@code SoundDetectionService}）が、写した後も同じに働くため。
     *
     * @param source          写す元の候補（答えのあるもの）
     * @param detectorVersion 今の検出器の版
     * @return 写した候補（保存前）
     */
    public static SoundCandidate carryOver(SoundCandidate source, String detectorVersion) {
        SoundCandidate copy = new SoundCandidate(
                source.getRecording(), source.getKind(), source.getPositionMs(), source.getScore(), detectorVersion);
        copy.verdict = source.getVerdict();
        copy.reviewedBy = source.getReviewedBy();
        copy.reviewedAt = source.getReviewedAt();
        copy.carriedFromId = source.getId();
        return copy;
    }

    /**
     * 聞いた人の答えを書き換える。答えは全員で共有し、最後の答えを有効にする（クラスの説明を参照）。
     *
     * <p>取り消し（{@code null}）では、答えた人と時刻も空にする。残すと、誰も答えていない候補なのに
     * 画面が「自分の答え」とみなして取り消しを出し続けるため（{@link #reviewedBy} の約束どおり、答えが無ければ空）。
     *
     * @param verdict  答え。{@code null} なら取り消し
     * @param reviewer 答えた利用者
     */
    public void review(Verdict verdict, AppUser reviewer) {
        this.verdict = verdict;
        this.reviewedBy = verdict == null ? null : reviewer;
        this.reviewedAt = verdict == null ? null : Instant.now();
    }

    /** 聞いた人の答え。 */
    public enum Verdict {
        /** 耳キスだった。 */
        CONFIRMED,
        /** 耳キスではなかった。 */
        REJECTED
    }
}
