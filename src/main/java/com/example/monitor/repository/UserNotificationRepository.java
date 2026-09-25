package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 利用者への通知の記録（{@link UserNotification}）の永続化を担当するリポジトリ。
 *
 * <p>書き込むのは巡回だけ。送った結果の記録には {@code save} ではなく列を絞った UPDATE を使う
 * （{@link MonitoredChannelRepository} と同じく、巡回の中で読み込み済みのエンティティを書き戻さない決まりに合わせる。
 * {@code docs/pitfalls.md}「監視ループから {@code save(entity)} を呼ばない」）。
 */
public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    /**
     * 利用者と配信の組の記録を引く。
     *
     * @param user    通知の相手
     * @param videoId 配信の動画 ID
     * @return 記録。まだ一度も送ろうとしていなければ {@link Optional#empty()}
     */
    Optional<UserNotification> findByUserAndVideoId(AppUser user, String videoId);

    /**
     * 送れたことを記録する。以後この組には送らない。
     *
     * @param id         記録の主キー
     * @param notifiedAt 送れた時刻
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserNotification n SET n.notifiedAt = :notifiedAt WHERE n.id = :id")
    int markNotified(@Param("id") Long id, @Param("notifiedAt") LocalDateTime notifiedAt);

    /**
     * 送れなかったことを記録する（失敗回数を 1 増やす）。
     *
     * @param id 記録の主キー
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserNotification n SET n.failureCount = n.failureCount + 1 WHERE n.id = :id")
    int incrementFailureCount(@Param("id") Long id);
}
