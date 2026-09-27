package com.example.monitor.repository;

import com.example.monitor.entity.DiscoveryCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 新人発掘の候補（{@link DiscoveryCandidate}）の読み書き。 */
public interface DiscoveryCandidateRepository extends JpaRepository<DiscoveryCandidate, String> {

    /**
     * 状態ごとに、見つけた（登録した）新しい順で返す。
     *
     * @param status 状態
     * @return 候補
     */
    List<DiscoveryCandidate> findByStatusOrderByDiscoveredAtDesc(DiscoveryCandidate.Status status);

    /**
     * 取り直しの時期が来た行を返す（30 日の決まり）。
     *
     * @param statuses 対象の状態
     * @param before   この時刻より前に取り直した行
     * @return 取り直す行
     */
    List<DiscoveryCandidate> findByStatusInAndRefreshedAtBefore(Collection<DiscoveryCandidate.Status> statuses,
                                                                Instant before);

    /**
     * 判定されないまま古くなった候補を返す（30 日の決まり）。
     *
     * @param status 状態
     * @param before この時刻より前に見つけた行
     * @return 消す行
     */
    List<DiscoveryCandidate> findByStatusAndDiscoveredAtBefore(DiscoveryCandidate.Status status, Instant before);
}
