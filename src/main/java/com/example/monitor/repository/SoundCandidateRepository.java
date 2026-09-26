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
     * @param recording       録画
     * @param kind            種類
     * @param detectorVersion 今の検出器の版（これ以外の版を引く）
     * @return 答えのある候補（答えた時刻の新しい順、同じなら id の順）
     */
    @Query("""
            SELECT c FROM SoundCandidate c
             WHERE c.recording = :recording AND c.kind = :kind AND c.detectorVersion <> :detectorVersion
               AND c.verdict IS NOT NULL
             ORDER BY c.reviewedAt DESC NULLS LAST, c.id
            """)
    List<SoundCandidate> findAnsweredInOtherVersions(@Param("recording") Recording recording,
                                                     @Param("kind") SoundMark.Kind kind,
                                                     @Param("detectorVersion") String detectorVersion);
}
