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

/**
 * 収集した外部動画（{@code OnlineVideo}）の検索と、巡回・収集からの更新。
 *
 * <p>認可条件をページ分割より前に適用し、他人の購読や件数を漏らさない。
 */
public interface OnlineVideoRepository extends JpaRepository<OnlineVideo, String> {
    String ACCESS = "(:admin = true or exists (select s.id from UserSubscription s where s.channel = v.channel and s.user.username = :username))";
    /**
     * 見てよい動画を、種類を問わず公開の新しい順に返す（一覧の {@code section} 省略時の従来の検索）。
     *
     * <p>ダッシュボードが {@code liveOnly=true} で使うため、段ごとの検索と分けて動きを変えずに残している。
     * 種類が未判定の動画も含む。
     *
     * @param admin     管理者なら {@code true}（すべてのチャンネルを見られる）
     * @param username  利用者のログイン ID。一般の利用者は購読しているチャンネルの動画だけを返す
     * @param channelId 絞り込むチャンネルの主キー。{@code null} なら絞らない
     * @param liveOnly  {@code true} なら確認済みの配信中（{@link #LIVE_NOW} と同じ条件）だけ
     * @param startedAt アプリの起動時刻。これより前の観測は配信中の確認に数えない
     * @param keyword   タイトル・チャンネル名に対する、大文字小文字を区別しない部分一致の検索語。空文字なら絞らない
     * @param pageable  ページ指定
     * @return 条件に一致する動画
     */
    @EntityGraph(attributePaths = "channel")
    @Query("select v from OnlineVideo v where " + ACCESS
        + " and (:channelId is null or v.channel.id = :channelId)"
        + " and (:liveOnly = false or (v.live = true and v.lastObservedAt >= :startedAt and v.channel.consecutiveDetectionFailures = 0))"
        + " and (lower(v.title) like lower(concat('%', :keyword, '%')) or lower(v.channel.channelName) like lower(concat('%', :keyword, '%')))"
        + " order by v.publishedAt desc, v.id desc")
    Page<OnlineVideo> search(@Param("admin") boolean admin, @Param("username") String username,
        @Param("channelId") Long channelId, @Param("liveOnly") boolean liveOnly,
        @Param("startedAt") Instant startedAt, @Param("keyword") String keyword, Pageable pageable);

    /** 段ごとの検索で共通の絞り込み。{@link #search} と同じ認可・チャンネル・キーワード条件を全段に効かせる。 */
    String SECTION_FILTER = ACCESS
        + " and (:channelId is null or v.channel.id = :channelId)"
        + " and (lower(v.title) like lower(concat('%', :keyword, '%')) or lower(v.channel.channelName) like lower(concat('%', :keyword, '%')))";
    /** {@code liveOnly=true} と同じ「配信中」。確認できていない配信を配信中として並べない。 */
    String LIVE_NOW = "(v.live = true and v.lastObservedAt >= :startedAt and v.channel.consecutiveDetectionFailures = 0)";

    /**
     * 配信中を先に、続けて開始予定の早い順。開始予定を過ぎてもまだ始まらない待機所も残すため、下限は設けない。
     * 未判定（{@code contentKind} が null）の動画は種類が分からないので出さない。
     */
    @EntityGraph(attributePaths = "channel")
    @Query("select v from OnlineVideo v where " + SECTION_FILTER
        + " and v.contentKind is not null"
        + " and (" + LIVE_NOW + " or (v.contentKind = 'UPCOMING' and v.scheduledStartTime < :until))"
        + " order by case when " + LIVE_NOW + " then 0 else 1 end, v.scheduledStartTime asc, v.id asc")
    Page<OnlineVideo> searchNow(@Param("admin") boolean admin, @Param("username") String username,
        @Param("channelId") Long channelId, @Param("startedAt") Instant startedAt, @Param("until") Instant until,
        @Param("keyword") String keyword, Pageable pageable);

    /**
     * 配信中のものは「配信中・配信予定」の段に出すため除く。状態を確認できていない配信は配信中の段に出ないので、
     * どの段からも消えないようこちらに残す（{@link #LIVE_NOW} の否定。null を含む比較で行が落ちないよう明示的に書く）。
     */
    @EntityGraph(attributePaths = "channel")
    @Query("select v from OnlineVideo v where " + SECTION_FILTER
        + " and v.contentKind = 'STREAM'"
        + " and (v.live = false or v.lastObservedAt is null or v.lastObservedAt < :startedAt or v.channel.consecutiveDetectionFailures <> 0)"
        + " order by v.publishedAt desc, v.id desc")
    Page<OnlineVideo> searchStreams(@Param("admin") boolean admin, @Param("username") String username,
        @Param("channelId") Long channelId, @Param("startedAt") Instant startedAt,
        @Param("keyword") String keyword, Pageable pageable);

    /**
     * 見てよい投稿動画（種類が {@code UPLOAD}）を公開の新しい順に返す（一覧の {@code section=uploads}）。
     *
     * <p>投稿動画は配信中にならないので、配信中の条件も起動時刻も使わない。
     *
     * @param admin     管理者なら {@code true}（すべてのチャンネルを見られる）
     * @param username  利用者のログイン ID。一般の利用者は購読しているチャンネルの動画だけを返す
     * @param channelId 絞り込むチャンネルの主キー。{@code null} なら絞らない
     * @param keyword   タイトル・チャンネル名に対する、大文字小文字を区別しない部分一致の検索語。空文字なら絞らない
     * @param pageable  ページ指定
     * @return 条件に一致する投稿動画
     */
    @EntityGraph(attributePaths = "channel")
    @Query("select v from OnlineVideo v where " + SECTION_FILTER
        + " and v.contentKind = 'UPLOAD'"
        + " order by v.publishedAt desc, v.id desc")
    Page<OnlineVideo> searchUploads(@Param("admin") boolean admin, @Param("username") String username,
        @Param("channelId") Long channelId, @Param("keyword") String keyword, Pageable pageable);

    /**
     * 見てよい動画を 1 件返す。
     *
     * <p>詳細とサムネイルの取得で、一覧と同じ購読の境界を効かせるため、主キーだけで引かずに認可条件を付ける。
     *
     * @param id       動画の主キー
     * @param admin    管理者なら {@code true}（すべてのチャンネルを見られる）
     * @param username 利用者のログイン ID
     * @return 該当する動画。無い・購読していないチャンネルの動画なら空
     */
    @EntityGraph(attributePaths = "channel")
    @Query("select v from OnlineVideo v where v.id = :id and " + ACCESS)
    Optional<OnlineVideo> findVisible(@Param("id") String id, @Param("admin") boolean admin,
                                    @Param("username") String username);

    /**
     * チャンネルの配信中の印を、今の配信以外から外す。
     *
     * <p>配信が終わったか別の配信に変わったとき、古い配信に配信中の印を残さないため、巡回の判定ごとに呼ぶ。
     * {@code currentId} に空文字を渡すと、そのチャンネルの配信中の印をすべて外す（配信中でないと判定できたとき。
     * 待機所を含む。判定できなかったときは呼ばれない）。
     *
     * @param channelId チャンネルの主キー
     * @param currentId 印を残す今の配信の主キー。空文字ならどの動画にも一致しないので、すべて外す
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update OnlineVideo v set v.live = false where v.channel.id = :channelId and v.id <> :currentId and v.live = true")
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

    /** 配信予定は開始すると種類が変わるため、未判定と一緒に判定し直す。新しく見つかった動画を先に判定する。 */
    @EntityGraph(attributePaths = "channel")
    @Query("""
        select v from OnlineVideo v
         where v.contentKind is null or v.contentKind = 'UPCOMING'
         order by v.discoveredAt desc, v.id desc
        """)
    List<OnlineVideo> pendingContentKind(Pageable pageable);

    /**
     * エンティティの save() で書き戻すと、判定の通信中にライブ検知が更新した列を古い値で消すため、2 列だけを更新する。
     * 判定中にライブ検知が {@code STREAM} にした動画は、取得済みの古い判定で上書きしない。
     */
    @Modifying @Transactional
    @Query("""
        update OnlineVideo v set v.contentKind = :kind, v.scheduledStartTime = :scheduledStartTime
         where v.id = :id and (v.contentKind is null or v.contentKind = 'UPCOMING')
        """)
    int updateContentKind(@Param("id") String id, @Param("kind") String kind,
                          @Param("scheduledStartTime") Instant scheduledStartTime);
}
