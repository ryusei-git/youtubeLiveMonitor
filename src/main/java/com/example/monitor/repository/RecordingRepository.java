package com.example.monitor.repository;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 録画履歴の永続化を担当するリポジトリ。
 *
 * <p>録画の完了・失敗は {@link com.example.monitor.service.StreamRecorder} が別スレッド
 * （仮想スレッド）から更新する。{@code save(entity)} ではなく対象カラムを絞った UPDATE を使うのは、
 * {@link MonitoredChannelRepository} と同じ理由（全カラム書き戻しによる意図しない上書きを避けるため）。
 */
public interface RecordingRepository extends JpaRepository<Recording, Long> {

    /**
     * 全チャンネルの録画履歴を新しい順に取得する。
     *
     * @param pageable ページ指定
     * @return 開始時刻の降順に並んだ履歴
     */
    Page<Recording> findAllByOrderByStartedAtDesc(Pageable pageable);

    /**
     * 特定チャンネルの録画履歴を新しい順に取得する。
     *
     * @param channel  対象チャンネル
     * @param pageable ページ指定
     * @return 開始時刻の降順に並んだ履歴
     */
    Page<Recording> findByChannelOrderByStartedAtDesc(MonitoredChannel channel, Pageable pageable);

    /**
     * 指定した複数チャンネルの録画を新しい順に返す。利用者が購読しているぶんだけを見せるのに使う。
     *
     * @param channels 対象のチャンネル
     * @param pageable ページ指定
     * @return 録画の一覧
     */
    Page<Recording> findByChannelInOrderByStartedAtDesc(
            java.util.Collection<MonitoredChannel> channels, Pageable pageable);

    /**
     * 購読範囲と検索条件をページング前に適用し、件数にも同じ条件を反映する。
     * チャンネルの指定だけで購読外の録画を取得できないよう、購読範囲は常に必須にする。
     *
     * @param channels 購読中のチャンネル
     * @param channelId 選択中のチャンネル。未指定なら全購読チャンネル
     * @param keyword タイトルの検索語。未指定なら絞り込まない
     * @param playableOnly 再生可能な録画だけに絞るか
     * @param pageable ページ指定
     * @return 条件に一致する録画
     */
    @Query("""
            SELECT r FROM Recording r
             WHERE r.channel IN :channels
               AND (:channelId IS NULL OR r.channel.id = :channelId)
               AND (:keyword IS NULL OR LOWER(r.videoTitle) LIKE LOWER(CONCAT('%', :keyword, '%')))
               AND (:playableOnly = false OR r.status IN (
                   com.example.monitor.entity.Recording.RecordingStatus.COMPLETED,
                   com.example.monitor.entity.Recording.RecordingStatus.PARTIAL))
             ORDER BY r.startedAt DESC
            """)
    Page<Recording> searchSubscribed(@Param("channels") Collection<MonitoredChannel> channels,
                                      @Param("channelId") Long channelId,
                                      @Param("keyword") String keyword,
                                      @Param("playableOnly") boolean playableOnly,
                                      Pageable pageable);

    /**
     * 録画履歴をキーワードと状態で絞り込んで新しい順に取得する。
     *
     * <p>キーワードは配信タイトルとチャンネル名のどちらかに部分一致すればよい
     * （利用者はどちらで覚えているか分からないため）。大文字小文字は区別しない。
     * 引数が {@code null} の条件はその条件自体を無視する、という書き方にしているので、
     * 「キーワードだけ」「状態だけ」「両方」「どちらも無し」を 1 つのクエリで賄える。
     *
     * <p><b>チャンネルへの結合は必ず {@code LEFT JOIN} にすること（実際に発生した）。</b>
     * 以前は {@code r.channel.channelName} と書いていたが、この書き方は<b>内部結合</b>になり、
     * チャンネルに紐づかない録画（URL 指定でダウンロードしたもの）が<b>検索条件に関わらず
     * 一覧から丸ごと消える</b>。しかも 1 件取得（{@code findById}）では見えるため、
     * 「API では取れるのに一覧に出ない」という分かりにくい形で現れる。
     *
     * @param keyword  検索キーワード。{@code null} なら絞り込まない
     * @param status   絞り込む状態。{@code null} なら絞り込まない
     * @param pageable ページ指定
     * @return 開始時刻の降順に並んだ履歴
     */
    @Query("""
            SELECT r FROM Recording r
             LEFT JOIN r.channel c
             WHERE (:keyword IS NULL
                    OR LOWER(r.videoTitle) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(c.channelName) LIKE LOWER(CONCAT('%', :keyword, '%')))
               AND (:status IS NULL OR r.status = :status)
             ORDER BY r.startedAt DESC
            """)
    Page<Recording> search(@Param("keyword") String keyword,
                           @Param("status") RecordingStatus status,
                           Pageable pageable);

    /**
     * 指定した状態の録画履歴をすべて取得する。
     *
     * <p>アプリ起動直後の「前回の起動中に置き去りになった {@code RECORDING} 行を補正する」処理
     * （{@link com.example.monitor.service.RecordingReconciler#reconcileOrphanedRecordings()}）
     * で使う。
     *
     * @param status 対象の状態
     * @return 該当する録画履歴の一覧
     */
    List<Recording> findByStatus(RecordingStatus status);

    /**
     * 指定した状態の録画履歴の件数を数える。
     *
     * <p>ダッシュボードの内訳グラフ用。一覧を読み込んで数えると件数が増えたときに無駄が大きいため、
     * COUNT で数えるだけにしている。
     *
     * @param status 対象の状態
     * @return 該当件数
     */
    long countByStatus(RecordingStatus status);

    /**
     * 録画完了として記録する。
     *
     * @param id            録画履歴の主キー
     * @param fileSizeBytes 完成したファイルのサイズ（バイト）
     * @param completedAt   完了した時刻
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Recording r
               SET r.status = com.example.monitor.entity.Recording.RecordingStatus.COMPLETED,
                   r.fileSizeBytes = :fileSizeBytes,
                   r.completedAt = :completedAt
             WHERE r.id = :id
            """)
    int markCompleted(@Param("id") Long id,
                       @Param("fileSizeBytes") long fileSizeBytes,
                       @Param("completedAt") LocalDateTime completedAt);

    /**
     * 途中までの録画として記録する。再生はできるが配信の最後までは録れていない状態。
     *
     * @param id            録画履歴の主キー
     * @param fileSizeBytes 再生できる状態に直したファイルのサイズ（バイト）
     * @param completedAt   録画が途切れたと確定した時刻
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Recording r
               SET r.status = com.example.monitor.entity.Recording.RecordingStatus.PARTIAL,
                   r.fileSizeBytes = :fileSizeBytes,
                   r.completedAt = :completedAt
             WHERE r.id = :id
            """)
    int markPartial(@Param("id") Long id,
                     @Param("fileSizeBytes") long fileSizeBytes,
                     @Param("completedAt") LocalDateTime completedAt);

    /**
     * 録画失敗として記録する。
     *
     * @param id          録画履歴の主キー
     * @param completedAt 失敗が確定した時刻
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Recording r
               SET r.status = com.example.monitor.entity.Recording.RecordingStatus.FAILED,
                   r.completedAt = :completedAt
             WHERE r.id = :id
            """)
    int markFailed(@Param("id") Long id, @Param("completedAt") LocalDateTime completedAt);

    /**
     * 一覧表示用の付加情報（再生時間・サムネイル）を記録する。
     *
     * <p>録画の成否とは独立に、後から埋められるようにしている。これらが取れなくても
     * 再生はできるため、録画の完了・失敗の記録とは分けている。
     *
     * @param id              録画履歴の主キー
     * @param durationSeconds 再生時間（秒）。読み取れなければ {@code null}
     * @param thumbnailPath   サムネイル画像の相対パス。生成できなければ {@code null}
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Recording r
               SET r.durationSeconds = :durationSeconds,
                   r.thumbnailPath = :thumbnailPath
             WHERE r.id = :id
            """)
    int updateMediaMetadata(@Param("id") Long id,
                             @Param("durationSeconds") Integer durationSeconds,
                             @Param("thumbnailPath") String thumbnailPath);

    /**
     * 指定チャンネルで録画履歴に記録されている動画IDの一覧を取得する。
     *
     * <p>{@link com.example.monitor.service.RecordingFileService#deleteOrphanedRecordings()} が、
     * 登録中チャンネルのディレクトリ内に残った「どの履歴にも紐づかない断片ファイル」を
     * 見分けるために使う。状態を問わず（{@code COMPLETED}/{@code RECORDING}/{@code FAILED}
     * のいずれも）返す。履歴が残っている動画IDのファイルは、状態に関わらず消してはいけないため。
     *
     * @param youtubeChannelId 対象チャンネルの YouTube チャンネル ID
     * @return 履歴が存在する動画IDの一覧
     */
    @Query("SELECT r.videoId FROM Recording r WHERE r.channel.youtubeChannelId = :youtubeChannelId")
    List<String> findVideoIdsByChannelYoutubeChannelId(@Param("youtubeChannelId") String youtubeChannelId);

    /**
     * どのチャンネルにも紐づいていない録画履歴を取得する。
     *
     * <p>URL 指定のダウンロード（{@link com.example.monitor.service.VideoDownloadService}）で
     * 取り込んだ、監視対象に登録されていないチャンネルの動画がこれにあたる。
     *
     * <p><b>{@link com.example.monitor.service.RecordingFileService#deleteOrphanedRecordings()}
     * がこれらのファイルを誤って消さないために要る。</b>あちらは「登録中のどのチャンネル ID とも
     * 一致しないディレクトリ」を削除対象にするため、チャンネルに紐づかない録画の置き場所は
     * そのままでは丸ごと削除の対象に見えてしまう。
     *
     * @return チャンネルに紐づいていない録画履歴の一覧
     */
    List<Recording> findByChannelIsNull();

    /**
     * 指定した動画 ID の録画履歴が既に存在するかを判定する。
     *
     * <p>同じ動画を二重にダウンロードしないための確認に使う
     * （状態は問わない。失敗した録画が残っている場合は、先にその履歴を削除してもらう）。
     *
     * @param videoId 確認する動画 ID
     * @return 履歴が存在すれば {@code true}
     */
    boolean existsByVideoId(String videoId);

    /**
     * 再生できる状態なのにサムネイルがまだ無い録画を取得する。
     *
     * <p>サムネイル生成の仕組みを入れる前に録画したものや、生成に失敗したものを
     * 後から埋めるために使う（{@link com.example.monitor.service.RecordingReconciler} が巡回のたびに拾う）。
     *
     * <p><b>状態を 1 つに固定せず引数で受けるのは、途中で切れた録画（{@code PARTIAL}）にも
     * サムネイルが要るため。</b>完了したものだけを対象にすると、途中までの録画は
     * 一覧で「サムネイル生成待ち」のまま永久に埋まらない。
     *
     * @param statuses 対象にする状態
     * @return 対象の録画履歴
     */
    List<Recording> findByStatusInAndThumbnailPathIsNull(Collection<RecordingStatus> statuses);
}
