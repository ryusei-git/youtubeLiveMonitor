package com.example.monitor.repository;

import com.example.monitor.entity.OnlineVideo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 認可条件をページ分割より前に適用し、他人の購読や件数を漏らさない。 */
public interface OnlineVideoRepository extends JpaRepository<OnlineVideo, String> {
    String ACCESS = "(:admin = true or exists (select s.id from UserSubscription s where s.channel = v.channel and s.user.username = :username))";
    @EntityGraph(attributePaths = "channel")
    @Query("select v from OnlineVideo v where " + ACCESS
        + " and (:channelId is null or v.channel.id = :channelId)"
        + " and (:liveOnly = false or (v.live = true and v.lastObservedAt >= :startedAt and v.channel.consecutiveDetectionFailures = 0))"
        + " and (lower(v.title) like lower(concat('%', :keyword, '%')) or lower(v.channel.channelName) like lower(concat('%', :keyword, '%')))"
        + " order by v.publishedAt desc, v.id desc")
    Page<OnlineVideo> search(@Param("admin") boolean admin, @Param("username") String username,
        @Param("channelId") Long channelId, @Param("liveOnly") boolean liveOnly,
        @Param("startedAt") Instant startedAt, @Param("keyword") String keyword, Pageable pageable);

    @EntityGraph(attributePaths = "channel")
    @Query("select v from OnlineVideo v where v.id = :id and " + ACCESS)
    Optional<OnlineVideo> findVisible(@Param("id") String id, @Param("admin") boolean admin,
                                    @Param("username") String username);

    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update OnlineVideo v set v.live = false where v.channel.id = :channelId and v.id <> :currentId")
    void endOtherStreams(@Param("channelId") Long channelId, @Param("currentId") String currentId);

    /** 未取得の新規動画を優先し、再試行は保存済みの時刻順に選ぶ。
     * 失敗行だけで先頭 100 件が埋まり、後から来た動画を取りこぼすのを防ぐ。
     */
    @Query("""
        select v from OnlineVideo v
         where not exists (select t.id from VideoThumbnail t where t.id = v.id)
           and v.thumbnailAttempts < :maxAttempts
           and (v.thumbnailNextAttemptAt is null or v.thumbnailNextAttemptAt <= :now)
         order by case when v.thumbnailAttempts = 0 then 0 else 1 end,
                  v.thumbnailNextAttemptAt asc, v.discoveredAt asc, v.id asc
        """)
    List<OnlineVideo> eligibleWithoutThumbnail(@Param("now") Instant now,
                                                @Param("maxAttempts") int maxAttempts, Pageable pageable);

    /** URL が収集中に変わった場合、古い URL の失敗を新しい URL に適用しない。 */
    @Modifying @Transactional
    @Query("""
        update OnlineVideo v set v.thumbnailAttempts = :attempts, v.thumbnailNextAttemptAt = :nextAttemptAt
         where v.id = :id and v.thumbnailAttempts = :previousAttempts
           and ((:thumbnailUrl is null and v.thumbnailUrl is null) or v.thumbnailUrl = :thumbnailUrl)
        """)
    int recordThumbnailFailure(@Param("id") String id, @Param("thumbnailUrl") String thumbnailUrl,
                               @Param("previousAttempts") int previousAttempts, @Param("attempts") int attempts,
                               @Param("nextAttemptAt") Instant nextAttemptAt);
}
