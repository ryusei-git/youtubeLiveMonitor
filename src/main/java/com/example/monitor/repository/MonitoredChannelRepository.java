package com.example.monitor.repository;

import com.example.monitor.entity.MonitoredChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 監視対象チャンネルの永続化を担当するリポジトリ。
 *
 * <p><b>監視ループからの更新には必ず本クラスの個別 UPDATE メソッドを使うこと。</b>
 * {@code save(entity)} はエンティティの全カラムを書き戻すため、
 * 監視ループがエンティティを読み込んでから書き戻すまでの間に
 * 管理用 API（{@link com.example.monitor.service.DatabaseTableService}）経由で
 * 別のカラムが更新されていた場合、その変更を古い値で消してしまう。
 * 実際にチャンネル名の変更が消える不具合が発生したため、更新対象カラムを絞ったメソッドを用意している。
 */
public interface MonitoredChannelRepository extends JpaRepository<MonitoredChannel, Long> {

    /**
     * YouTube のチャンネル ID で監視対象を検索する。
     *
     * @param youtubeChannelId YouTube が発行するチャンネル ID
     * @return 見つかった監視対象。未登録なら {@link Optional#empty()}
     */
    Optional<MonitoredChannel> findByYoutubeChannelId(String youtubeChannelId);

    /**
     * 指定した YouTube チャンネルが既に登録済みかを判定する。
     *
     * @param youtubeChannelId YouTube が発行するチャンネル ID
     * @return 登録済みなら {@code true}
     */
    boolean existsByYoutubeChannelId(String youtubeChannelId);

    /**
     * 配信状態を「正しく判定できた」ときの観測結果を記録する。
     *
     * <p>判定に成功した証として、連続失敗回数を 0 に戻し判定成功時刻も更新する。
     * 判定できなかった場合はこちらではなく {@link #recordDetectionFailure} を呼ぶこと。
     *
     * @param id        監視対象の主キー
     * @param live      配信中と判定されたか
     * @param videoId   配信中の動画 ID。配信していなければ {@code null}
     * @param checkedAt 監視した時刻
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE MonitoredChannel c
               SET c.currentlyLive = :live,
                   c.currentLiveVideoId = :videoId,
                   c.lastCheckedAt = :checkedAt,
                   c.lastDetectionSuccessAt = :checkedAt,
                   c.consecutiveDetectionFailures = 0
             WHERE c.id = :id
            """)
    int updateObservedLiveState(@Param("id") Long id,
                                 @Param("live") boolean live,
                                 @Param("videoId") String videoId,
                                 @Param("checkedAt") LocalDateTime checkedAt);

    /**
     * 配信状態を判定できなかったことを記録する。
     *
     * <p><b>{@code currentlyLive} と {@code currentLiveVideoId} には触れない。</b>
     * 判定できていない以上、配信中かどうかは分からないためで、ここで {@code false} を
     * 書いてしまうと「正常に調べた結果、配信していなかった」と区別がつかなくなる
     * （この取り違えがサイレント故障の温床だった）。直前に分かっている状態をそのまま残す。
     *
     * @param id        監視対象の主キー
     * @param checkedAt 監視を試みた時刻
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE MonitoredChannel c
               SET c.lastCheckedAt = :checkedAt,
                   c.consecutiveDetectionFailures = c.consecutiveDetectionFailures + 1
             WHERE c.id = :id
            """)
    int recordDetectionFailure(@Param("id") Long id, @Param("checkedAt") LocalDateTime checkedAt);

    /**
     * 配信開始前の待機所として検知した予定を記録する。
     *
     * <p>{@code UPCOMING} を検知するたびに呼ぶこと。前回と同じ動画IDであっても、
     * タイトルや開始予定時刻が更新されている可能性があるため毎回上書きする。
     *
     * @param id                 監視対象の主キー
     * @param videoId            予約枠の動画ID
     * @param title              予定配信のタイトル。取得できなかった場合は {@code null}
     * @param scheduledStartTime 開始予定時刻。取得できなかった場合は {@code null}
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE MonitoredChannel c
               SET c.upcomingVideoId = :videoId,
                   c.upcomingTitle = :title,
                   c.upcomingScheduledStartTime = :scheduledStartTime
             WHERE c.id = :id
            """)
    int updateUpcoming(@Param("id") Long id, @Param("videoId") String videoId,
                        @Param("title") String title,
                        @Param("scheduledStartTime") LocalDateTime scheduledStartTime);

    /**
     * 配信予定の記録を消す。
     *
     * <p>{@code LIVE}（予定が現実になった）または {@code NOT_LIVE}（予定が消えた）を
     * 検知したときに呼ぶこと。{@code DETECTION_FAILED} のときは呼んではならない
     * （判定できなかっただけなのに「予定が無い」と記録してしまい、区別が付かなくなるため）。
     *
     * @param id 監視対象の主キー
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE MonitoredChannel c
               SET c.upcomingVideoId = null,
                   c.upcomingTitle = null,
                   c.upcomingScheduledStartTime = null
             WHERE c.id = :id
            """)
    int clearUpcoming(@Param("id") Long id);

    /**
     * 通知済みの動画 ID のみを更新する。通知が成功したときだけ呼ぶこと。
     *
     * <p>失敗時に更新しないでおくことで、次回の監視サイクルが自動的に再送信の役割を果たす。
     *
     * @param id      監視対象の主キー
     * @param videoId 通知に成功した配信の動画 ID
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE MonitoredChannel c
               SET c.lastNotifiedVideoId = :videoId,
                   c.notificationFailureCount = 0
             WHERE c.id = :id
            """)
    int updateLastNotifiedVideoId(@Param("id") Long id, @Param("videoId") String videoId);

    /**
     * 通知の送信に失敗した回数を 1 つ増やす。
     *
     * <p>再送信を諦める判断（{@code LiveStreamPollingScheduler} 側）に使う。
     * 無制限に再送信を繰り返すと失敗履歴が際限なく増え、クォータも消費し続けるため。
     *
     * @param id 監視対象の主キー
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE MonitoredChannel c
               SET c.notificationFailureCount = c.notificationFailureCount + 1
             WHERE c.id = :id
            """)
    int incrementNotificationFailureCount(@Param("id") Long id);

    /**
     * 通知の失敗回数を 0 に戻す。別の配信を検知したときに呼ぶ。
     *
     * <p>失敗回数は「今の配信に対して何回失敗したか」なので、配信が変われば
     * 新しい配信として改めて試行できるようにする必要がある。
     *
     * @param id 監視対象の主キー
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE MonitoredChannel c SET c.notificationFailureCount = 0 WHERE c.id = :id")
    int resetNotificationFailureCount(@Param("id") Long id);

    /**
     * 録画を開始した動画 ID のみを更新する。録画プロセスの起動に成功したときだけ呼ぶこと。
     *
     * <p>起動に失敗したときに更新しないでおくことで、次回の監視サイクルで
     * 自動的に録画開始が再試行される（通知の再試行と同じ考え方）。
     *
     * @param id      監視対象の主キー
     * @param videoId 録画を開始した配信の動画 ID
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE MonitoredChannel c SET c.lastRecordedVideoId = :videoId WHERE c.id = :id")
    int updateLastRecordedVideoId(@Param("id") Long id, @Param("videoId") String videoId);

    /**
     * 自動録画の有効・無効を切り替える。
     *
     * @param id             監視対象の主キー
     * @param recordEnabled 録画を有効にするか
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE MonitoredChannel c SET c.recordEnabled = :recordEnabled WHERE c.id = :id")
    int updateRecordEnabled(@Param("id") Long id, @Param("recordEnabled") boolean recordEnabled);

    /**
     * 録画対象を絞り込むタイトルキーワードを更新する。
     *
     * @param id            監視対象の主キー
     * @param titleKeywords 絞り込みキーワード（カンマ区切り）。空またはnullで絞り込み解除
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE MonitoredChannel c SET c.recordTitleKeywords = :titleKeywords WHERE c.id = :id")
    int updateRecordTitleKeywords(@Param("id") Long id, @Param("titleKeywords") String titleKeywords);
    /** ライブラリの絞り込みと取得状態にも、購読の境界を適用する。 */
    @Query("select c from MonitoredChannel c where :admin = true or exists (select s.id from UserSubscription s where s.channel = c and s.user.username = :username) order by c.channelName")
    java.util.List<MonitoredChannel> findLibraryChannels(@Param("admin") boolean admin, @Param("username") String username);

}
