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

    @Query("select v from OnlineVideo v where not exists (select t.id from VideoThumbnail t where t.id = v.id)")
    List<OnlineVideo> withoutThumbnail(Pageable pageable);
}
