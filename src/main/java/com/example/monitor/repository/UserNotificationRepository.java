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
     * 送れなかったことを記録する。失敗回数を 1 増やし、失敗の時刻と理由を残す。
     *
     * @param id       記録の主キー
     * @param failedAt 失敗した時刻
     * @param error    失敗の理由。{@link UserNotification} の列の長さ（200 文字）に切ってから渡す
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserNotification n SET n.failureCount = n.failureCount + 1,"
            + " n.lastFailedAt = :failedAt, n.lastError = :error WHERE n.id = :id")
    int recordFailure(@Param("id") Long id, @Param("failedAt") LocalDateTime failedAt,
                      @Param("error") String error);

    /**
     * 利用者の失敗の時刻と理由を消す。Webhook を登録し直したときに呼ぶ。
     *
     * <p>前の Webhook での失敗が残っていると、新しい Webhook がまだ一度も試されていないのに
     * 「最近の通知が届いていない」と見えてしまう。登録した時刻を {@code AppUser} に持って比べる方法もあるが、
     * 列を 1 つ増やして比べるより、古い失敗を消す方が判定が単純になる。
     * 失敗回数は消さない（配信ごとの再試行の上限は Webhook を変えても数え直さない）。
     *
     * @param user 利用者
     * @return 更新した件数
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserNotification n SET n.lastFailedAt = null, n.lastError = null"
            + " WHERE n.user = :user AND n.lastFailedAt IS NOT NULL")
    int clearFailures(@Param("user") AppUser user);

    /**
     * 利用者へ最後に送れた時刻を返す。
     *
     * @param user 利用者
     * @return 最後に送れた時刻。一度も送れていなければ {@code null}
     */
    @Query("SELECT MAX(n.notifiedAt) FROM UserNotification n WHERE n.user = :user")
    LocalDateTime findLastDeliveredAt(@Param("user") AppUser user);

    /**
     * 利用者への送信に最後に失敗した時刻を返す。
     *
     * @param user 利用者
     * @return 最後に失敗した時刻。失敗が無ければ（または Webhook を登録し直した後に失敗が無ければ）{@code null}
     */
    @Query("SELECT MAX(n.lastFailedAt) FROM UserNotification n WHERE n.user = :user")
    LocalDateTime findLastFailedAt(@Param("user") AppUser user);
}
