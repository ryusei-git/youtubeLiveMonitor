package com.example.monitor.repository;

import com.example.monitor.entity.Recording;
import com.example.monitor.entity.SoundCandidate;
import com.example.monitor.entity.SoundMark;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
