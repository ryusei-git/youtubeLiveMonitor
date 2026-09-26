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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * 録画 1 本に、ある版の検出器を掛けた記録（Issue #469）。見回りが「もう検出したか」を決めるのに使う。
 *
 * <h2>{@code (recording_id, kind, detector_version)} の一意制約</h2>
 * 同じ組の記録が 2 つあると、完了なのか失敗なのか決められなくなる。版を上げたら別の行になるので、
 * 新しい版の見回りは、古い版で完了した録画も改めて対象にする（学び直した重みで付け直すため）。
 *
 * <h2>回数（{@link #attempts}）を検出の前に増やす理由</h2>
 * 検出の途中で JVM が落ちる（本番は {@code -XX:+ExitOnOutOfMemoryError}）と、終わった後に書く記録は残らない。
 * 始める前に「失敗・回数 +1・実行中に止まった」を書いておけば、落ちても回数が増え、
 * {@link #MAX_ATTEMPTS} 回で見回りの対象から外れる。落ちては起動して同じ録画でまた落ちる、を繰り返さない。
 *
 * <p>録画が消えれば記録の意味も無くなるので、{@link SoundCandidate} と同じく DB 側の
 * {@code ON DELETE CASCADE} で一緒に消す。
 */
@Entity
@Table(name = "sound_detection_runs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sound_detection_runs_recording_id_kind_version",
                columnNames = {"recording_id", "kind", "detector_version"}))
@Getter
@NoArgsConstructor
public class SoundDetectionRun {

    /**
     * 失敗がこの回数に達したら、見回りの対象から外す。
     *
     * <p>ffmpeg が読めない録画のように何度やっても失敗する録画に、見回りのたびに CPU を使い続けないため。
     * 一時的な失敗（再起動で止まったなど）は、この回数までは次の見回りで試し直す。
     */
    public static final int MAX_ATTEMPTS = 3;

    /** 検出の前に書いておく理由。検出が終われば結果で上書きされるので、残っていれば途中で止まった。 */
    private static final String INTERRUPTED_MESSAGE = "実行中に止まった";

    /** {@link #message} の長さの上限。 */
    private static final int MESSAGE_LENGTH = 500;

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 検出した録画。録画を削除すると DB 側で一緒に消える。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recording_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Recording recording;

    /** 検出した種類。文字列の列にする理由は {@link SoundCandidate} の {@code kind} と同じ。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private SoundMark.Kind kind;

    /** 検出器の版（重みのファイルの {@code version}）。 */
    @Column(nullable = false, length = 40)
    private String detectorVersion;

    /** 結果。文字列の列にする理由は {@link SoundCandidate} の {@code kind} と同じ。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private Status status;

    /** 検出器が出した候補の数。完了のときだけ意味がある（答えのある候補の近くで作らなかった分も数える）。 */
    @Column(nullable = false)
    private int candidateCount;

    /** 検出を始めた回数（完了した回も含む）。 */
    @Column(nullable = false)
    private int attempts;

    /** 失敗の理由（500 文字まで）。完了なら {@code null}。 */
    @Column(length = MESSAGE_LENGTH)
    private String message;

    /** 最後に検出を始めた時刻。 */
    @Column(nullable = false)
    private Instant startedAt;

    /** 最後の検出が終わった時刻。途中で止まったときは {@code null}。 */
    private Instant finishedAt;

    /**
     * まだ一度も始めていない記録を作る。{@link #start()} などで状態を決めてから保存すること。
     *
     * @param recording       録画
     * @param kind            種類
     * @param detectorVersion 検出器の版
     */
    public SoundDetectionRun(Recording recording, SoundMark.Kind kind, String detectorVersion) {
        this.recording = recording;
        this.kind = kind;
        this.detectorVersion = detectorVersion;
    }

    /** 検出を始める。終わる前に止まっても失敗として数えられるよう、先に「失敗・回数 +1」にしておく。 */
    public void start() {
        status = Status.FAILED;
        attempts++;
        candidateCount = 0;
        message = INTERRUPTED_MESSAGE;
        startedAt = Instant.now();
        finishedAt = null;
    }

    /**
     * 検出が終わった。
     *
     * @param candidateCount 検出器が出した候補の数
     */
    public void finish(int candidateCount) {
        status = Status.DONE;
        this.candidateCount = candidateCount;
        message = null;
        finishedAt = Instant.now();
    }

    /**
     * 検出に失敗した。回数は {@link #start()} で増えている。
     *
     * @param reason 失敗の理由。500 文字を超える分は捨てる
     */
    public void fail(String reason) {
        status = Status.FAILED;
        message = reason.length() > MESSAGE_LENGTH ? reason.substring(0, MESSAGE_LENGTH) : reason;
        finishedAt = Instant.now();
    }

    /**
     * 検出せずに、見回りの対象から外す（長すぎる録画など）。回数を上限にして、次の見回りで選ばれないようにする。
     *
     * @param reason 外す理由
     */
    public void giveUp(String reason) {
        status = Status.FAILED;
        attempts = MAX_ATTEMPTS;
        candidateCount = 0;
        message = reason;
        startedAt = Instant.now();
        finishedAt = startedAt;
    }

    /** 検出の結果。 */
    public enum Status {
        /** 検出が終わり、候補を保存した。 */
        DONE,
        /** 失敗した、途中で止まった、または検出しないと決めた。理由は {@link SoundDetectionRun#message} にある。 */
        FAILED
    }
}
