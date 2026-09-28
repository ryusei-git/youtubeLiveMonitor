package com.example.monitor.repository;

import com.example.monitor.entity.Recording;
import com.example.monitor.entity.SoundCandidate;
import com.example.monitor.entity.SoundMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 耳キスなどの候補（{@link SoundCandidate}）の永続化を担当するリポジトリ。 */
public interface SoundCandidateRepository extends JpaRepository<SoundCandidate, Long> {

    /**
     * 録画に、ある版の検出器が付けた、ある種類の候補を引く。答えのあるものも含む。
     *
     * @param recording       録画
     * @param kind            種類
     * @param detectorVersion 検出器の版
     * @return 候補の一覧（順不同）
     */
    List<SoundCandidate> findByRecordingAndKindAndDetectorVersion(
            Recording recording, SoundMark.Kind kind, String detectorVersion);

    /**
     * 録画に付いた、ある種類の候補のうち、ほかの版の、答えのある候補を、答えた時刻の新しい順に引く
     * （付け直しで今の版へ写す元。Issue #481）。
     *
     * <p>新しい順にするのは、同じ位置の答えが複数の版にあるとき、いちばん新しい答えを写すため
     * （呼び出し側は先に写したものの近くを飛ばす）。答えた時刻の無い行（SQL で入れた答えなど）は最後に回す。
     *
     * <p><b>すでにほかの行へ写された行（どれかの行の {@code carriedFromId} が指している行）は引かない。</b>
     * 答えの API は今の版の候補しか書き換えないので、写した後の元の行は写した時点の答えのまま止まっていて、
     * 利用者のいまの答えは写した行（写しの鎖の末端）にしか無い。写した行の答えを取り消すと、写した行は答えの無い行になって
     * ここに出なくなる。そのとき元の行を引くと、次に版を上げたときに取り消す前の答えが写り、取り消しが黙って元に戻る。
     * 末端だけを写し元にすれば、末端を取り消した位置は何も写さない。
     *
     * <p>限界：版を古い版へ戻すと、写された元の行がまた今の版の候補になって答え直せるが、その答えは次に版を上げても
     * 写らない（写した行の方を写す）。版は学び直しで上げるだけで戻さない前提にしている。取り消しは答えた時刻も消すので、
     * 時刻を比べて両方を正しく扱うことはできない。
     *
     * <p>写した行を探す範囲を同じ録画に限るのは、{@code (recording_id, kind, detector_version)} の索引を使わせるため
     * （写した行は必ず元の行と同じ録画にある）。
     *
     * @param recording       録画
     * @param kind            種類
     * @param detectorVersion 今の検出器の版（これ以外の版を引く）
     * @return 答えのある、まだどの行にも写されていない候補（答えた時刻の新しい順、同じなら id の順）
     */
    @Query("""
            SELECT c FROM SoundCandidate c
             WHERE c.recording = :recording AND c.kind = :kind AND c.detectorVersion <> :detectorVersion
               AND c.verdict IS NOT NULL
               AND NOT EXISTS (
                    SELECT carried.id FROM SoundCandidate carried
                     WHERE carried.recording = c.recording AND carried.carriedFromId = c.id)
             ORDER BY c.reviewedAt DESC NULLS LAST, c.id
            """)
    List<SoundCandidate> findAnsweredInOtherVersions(@Param("recording") Recording recording,
                                                     @Param("kind") SoundMark.Kind kind,
                                                     @Param("detectorVersion") String detectorVersion);
}
