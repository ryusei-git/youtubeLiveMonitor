package com.example.monitor.repository;

import com.example.monitor.dto.RecordingGenreCountResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     * 指定した複数チャンネルの録画を新しい順に返す。利用者が購読しているぶんだけを見せるのに使う。
     *
     * @param channels 対象のチャンネル
     * @param pageable ページ指定
     * @return 録画の一覧
     */
    Page<Recording> findByChannelInOrderByStartedAtDesc(
            java.util.Collection<MonitoredChannel> channels, Pageable pageable);

    /**
     * 録画履歴をチャンネル・キーワード・状態・期間・ジャンルで絞り込んで取得する。
     *
     * <p>キーワードは配信タイトルとチャンネル名のどちらかに部分一致すればよい
     * （利用者はどちらで覚えているか分からないため）。大文字小文字は区別しない。
     * 引数が {@code null} の条件はその条件自体を無視する、という書き方にしているので、
     * どの組み合わせも 1 つのクエリで賄える（以前はチャンネル指定だけ別メソッドに分かれていて、
     * チャンネル一覧から来たときにキーワードで絞れなかった）。
     *
     * <p>並び順はクエリに書かず {@code pageable} の {@code Sort} で受ける。並び順ごとに
     * クエリを複製すると、絞り込み条件を直すたびに全部を揃える必要が出るため。
     *
     * <p><b>チャンネルへの結合は必ず {@code LEFT JOIN} にすること（実際に発生した）。</b>
     * 以前は {@code r.channel.channelName} と書いていたが、この書き方は<b>内部結合</b>になり、
     * チャンネルに紐づかない録画（URL 指定でダウンロードしたもの）が<b>検索条件に関わらず
     * 一覧から丸ごと消える</b>。しかも 1 件取得（{@code findById}）では見えるため、
     * 「API では取れるのに一覧に出ない」という分かりにくい形で現れる。
     * チャンネルでの絞り込みも同じ理由で {@code c.id} を見る。
     *
     * <p>視聴済み・お気に入りは、利用者の印（{@link com.example.monitor.entity.RecordingMark}）を
     * 同じく {@code LEFT JOIN} して見る。印の行は最初に印を付けたときに初めて作るため、
     * 行の無い録画を「未視聴・お気に入りでない」として残すには外部結合でなければならない。
     * 結合条件に利用者を入れているので、他の利用者の印で行が増えることはない。
     *
     * <p><b>購読の限定（{@code subscribedOnly}）は、利用者の画面と管理者の画面で同じクエリを使うために
     * ここに足している。</b>利用者用に別のクエリを書くと、絞り込み条件を直すたびに 2 つを揃える必要が
     * 出て、片方だけ直す事故の元になる（以前は {@code searchSubscribed} として別に持っていた）。
     * 購読をページングの前に効かせるので、件数にも購読外の録画は数えられない。
     * 購読が 0 件なら {@code EXISTS} が常に偽になり、空の結果になる。
     * チャンネルに紐づかない録画（URL 指定のダウンロード）は {@code c} が {@code null} なので、
     * 購読に限るときは出ない。
     *
     * <p><b>チャンネルも同時に読み込む（{@code @EntityGraph}）。</b>一覧の詰め替え
     * （{@link com.example.monitor.dto.RecordingResponse}）はコントローラー、つまりトランザクションの外で
     * チャンネル名を読むため。{@code spring.jpa.open-in-view} を切っているので、遅延読み込みのままだと
     * LazyInitializationException になる。上の {@code LEFT JOIN r.channel c} は絞り込みのための結合で、
     * 読み込みは {@code @EntityGraph} に任せている（{@link OnlineVideoRepository} の検索と同じく、
     * {@code @Query} とページ指定に {@code @EntityGraph} を併せて使う）。
     *
     * @param userId    印を見る利用者の主キー。{@code null} なら印は無いものとして扱う
     * @param subscribedOnly {@code userId} の利用者が購読しているチャンネルの録画だけに絞るか
     * @param channelId 絞り込むチャンネルの主キー。{@code null} なら絞り込まない
     * @param keyword   検索キーワード。{@code null} なら絞り込まない
     * @param status    絞り込む状態。{@code null} なら絞り込まない
     * @param from      開始時刻の下限（この時刻を含む）。{@code null} なら絞り込まない
     * @param to        開始時刻の上限（この時刻を含まない）。{@code null} なら絞り込まない
     * @param genre     ジャンル（完全一致）。{@code null} なら絞り込まない
     * @param watched   {@code true} なら視聴済みだけ、{@code false} なら未視聴だけ。{@code null} なら絞り込まない
     * @param favoriteOnly お気に入りだけに絞るか
     * @param playableOnly 再生できる録画（完了・途中まで）だけに絞るか
     * @param pageable  ページ指定と並び順
     * @return 条件に一致する録画履歴（チャンネル読み込み済み）
     */
    @EntityGraph(attributePaths = "channel")
    @Query("""
            SELECT r FROM Recording r
             LEFT JOIN r.channel c
             LEFT JOIN RecordingMark m ON m.recording = r AND m.user.id = :userId
             WHERE (:subscribedOnly = FALSE OR EXISTS (
                    SELECT s.id FROM UserSubscription s WHERE s.channel = c AND s.user.id = :userId))
               AND (:channelId IS NULL OR c.id = :channelId)
               AND (:keyword IS NULL
                    OR LOWER(r.videoTitle) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(c.channelName) LIKE LOWER(CONCAT('%', :keyword, '%')))
               AND (:status IS NULL OR r.status = :status)
               AND (:from IS NULL OR r.startedAt >= :from)
               AND (:to IS NULL OR r.startedAt < :to)
               AND (:genre IS NULL OR r.genre = :genre)
               AND (:watched IS NULL
                    OR (:watched = TRUE AND m.watchedAt IS NOT NULL)
                    OR (:watched = FALSE AND m.watchedAt IS NULL))
               AND (:favoriteOnly = FALSE OR m.favorite = TRUE)
               AND (:playableOnly = FALSE OR r.status IN (
                    com.example.monitor.entity.Recording.RecordingStatus.COMPLETED,
                    com.example.monitor.entity.Recording.RecordingStatus.PARTIAL))
            """)
    Page<Recording> search(@Param("userId") Long userId,
                           @Param("subscribedOnly") boolean subscribedOnly,
                           @Param("channelId") Long channelId,
                           @Param("keyword") String keyword,
                           @Param("status") RecordingStatus status,
                           @Param("from") LocalDateTime from,
                           @Param("to") LocalDateTime to,
                           @Param("genre") String genre,
                           @Param("watched") Boolean watched,
                           @Param("favoriteOnly") boolean favoriteOnly,
                           @Param("playableOnly") boolean playableOnly,
                           Pageable pageable);

    /**
     * ジャンルごとの録画件数を、件数の多い順（同数ならジャンル名順）に数える。
     *
     * <p>一覧画面のジャンル選択の選択肢に使う。選択肢を決め打ちにすると、録画が増えて
     * 新しいジャンルが現れても選べないため、実データから作る。ジャンルの無い録画は含めない。
     *
     * <p>購読の限定は {@link #search} と同じ条件。利用者の画面に購読外のジャンル
     * （＝購読外の録画があること）を出さないため。
     *
     * <p>再生可能の絞り込みも {@link #search} の {@code playableOnly} と同じ条件。選択肢の件数は、
     * そのジャンルを選んだときの一覧の件数と一致しなければならない。再生できる録画だけを出す一覧に対して
     * 失敗・録画中まで数えると、「歌枠（14）」を選んで 13 件しか出ない、という食い違いになる（#228）。
     *
     * @param subscriberId 購読しているチャンネルの録画だけを数える利用者の主キー。{@code null} なら全録画
     * @param playableOnly 再生できる録画（完了・途中まで）だけを数えるか
     * @return ジャンルと件数の一覧
     */
    @Query("""
            SELECT new com.example.monitor.dto.RecordingGenreCountResponse(r.genre, COUNT(r))
              FROM Recording r
             WHERE r.genre IS NOT NULL
               AND (:subscriberId IS NULL OR EXISTS (
                    SELECT s.id FROM UserSubscription s WHERE s.channel = r.channel AND s.user.id = :subscriberId))
               AND (:playableOnly = FALSE OR r.status IN (
                    com.example.monitor.entity.Recording.RecordingStatus.COMPLETED,
                    com.example.monitor.entity.Recording.RecordingStatus.PARTIAL))
             GROUP BY r.genre
             ORDER BY COUNT(r) DESC, r.genre ASC
            """)
    List<RecordingGenreCountResponse> countByGenre(@Param("subscriberId") Long subscriberId,
                                                   @Param("playableOnly") boolean playableOnly);

    /**
     * ジャンルが未設定でタイトルのある録画を取得する。{@code genre} 列を足す前の行を埋めるのに使う
     * （{@link com.example.monitor.service.RecordingGenreBackfiller} 参照）。
     *
     * @return 対象の録画履歴
     */
    List<Recording> findByGenreIsNullAndVideoTitleIsNotNull();

    /**
     * ジャンルだけを書き込む。
     *
     * @param id    録画履歴の主キー
     * @param genre 書き込むジャンル
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE Recording r SET r.genre = :genre WHERE r.id = :id")
    int updateGenre(@Param("id") Long id, @Param("genre") String genre);

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
     * 指定した状態の録画履歴を、チャンネルも同時に読み込んで取得する。
     *
     * <p>リソース計測で録画プロセスにチャンネル名を付けるのに使う。計測はトランザクションの外
     * （1 分ごとの定期処理）で動くため、遅延読み込みのままではチャンネル名を読めない。
     *
     * @param status 対象の状態
     * @return 該当する録画履歴（チャンネル読み込み済み）
     */
    @EntityGraph(attributePaths = "channel")
    List<Recording> findWithChannelByStatus(RecordingStatus status);

    /**
     * 録画履歴を 1 件、チャンネルも同時に読み込んで取得する。
     *
     * <p>継承したままではチャンネルが遅延読み込みになるので、{@code @EntityGraph} を付けるためだけに
     * 宣言し直している。再生画面の 1 件取得（{@code GET /api/recordings/{id}}・{@code GET /api/my/recordings/{id}}）は、
     * コントローラー（トランザクションの外）で {@link com.example.monitor.dto.RecordingResponse} に詰め替えて
     * チャンネル名を読む。{@code spring.jpa.open-in-view} を切っているので、遅延読み込みのままだと
     * LazyInitializationException になる。ほかの呼び出し元（印・耳キス・削除・録画の停止・録画の後始末の補正・CLI）は
     * チャンネルを読まないが、1 件につき 1 回の結合なので、専用のメソッドには分けていない。
     *
     * @param id 録画履歴の主キー
     * @return 該当する録画履歴（チャンネル読み込み済み）。無ければ空
     */
    @Override
    @EntityGraph(attributePaths = "channel")
    Optional<Recording> findById(Long id);

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
     * 指定した状態で、開始が指定時刻以降の録画を新しい順に最大 20 件取得する。
     *
     * <p>ダッシュボードの「直近の録画失敗」に使う。チャンネル名とリンクを必ず読むため
     * チャンネルも同時に取得する（遅延読み込みのままだと、トランザクションの外で変換したときに
     * 読めず、1 件ずつ追加の問い合わせも走る）。
     *
     * @param status 対象の状態
     * @param since  開始時刻の下限（この時刻を含む）
     * @return 該当する録画履歴
     */
    @EntityGraph(attributePaths = "channel")
    List<Recording> findTop20ByStatusAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            RecordingStatus status, LocalDateTime since);

    /**
     * チャンネルごとに、指定した状態の録画の件数を数える。
     *
     * <p>チャンネル一覧に件数を添えるために使う。チャンネルごとに問い合わせると
     * 登録数に比例してクエリが増えるため、1 回の GROUP BY でまとめて数える。
     * チャンネルに紐づかない録画（URL 指定のダウンロード）は数えない。
     *
     * @param statuses 数える対象の状態
     * @return 各要素が {@code [チャンネルの主キー(Long), 件数(Long)]} の配列。録画が 1 件も無いチャンネルは含まない
     */
    @Query("""
            SELECT r.channel.id, COUNT(r) FROM Recording r
             WHERE r.channel IS NOT NULL AND r.status IN :statuses
             GROUP BY r.channel.id
            """)
    List<Object[]> countByChannel(@Param("statuses") Collection<RecordingStatus> statuses);

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
     * 再生回数に 1 を足す。
     *
     * <p>エンティティを読んで {@code save} し直さず UPDATE 文で足すのは、同時に再生されたときに
     * 片方の加算を上書きして数え落とさないため。
     *
     * @param id 録画履歴の主キー
     * @return 更新した件数。対象の行が無ければ 0
     */
    @Modifying
    @Transactional
    @Query("UPDATE Recording r SET r.playCount = r.playCount + 1 WHERE r.id = :id")
    int incrementPlayCount(@Param("id") Long id);

    /**
     * 録画履歴に記録されている動画IDを、状態・チャンネルを問わずすべて取得する。
     *
     * <p>{@link com.example.monitor.service.OrphanedPreviewService#preview()} が、履歴のある動画の
     * ファイルを削除候補から外すのに使う。使うのは動画IDだけなので、{@code findAll()} のように
     * 全行・全列のエンティティを作らない（#184）。
     *
     * @return 履歴が存在する動画IDの一覧（同じ動画IDが複数回含まれることがある）
     */
    @Query("SELECT r.videoId FROM Recording r")
    List<String> findAllVideoIds();

    /**
     * チャンネルの録画中（{@code RECORDING}）の録画の保存先（{@code filePath}）を取得する。
     *
     * <p>{@link com.example.monitor.service.MonitoredChannelService#remove(Long)} が、チャンネルを消す前に
     * 止めるべき録画を集めるのに使う。消した後では録画履歴も連鎖削除で消えていて引けない。
     * 動画 ID ではなく保存先を返すのは、止める yt-dlp を出力先で探すため。動画 ID で探すと、
     * 同じ動画を「端末に保存」している利用者の yt-dlp まで止めてしまう。
     *
     * @param channelId 監視対象の主キー
     * @return 録画中の録画の保存先（{@code <チャンネルID>/<動画ID>.mp4}）の一覧
     */
    @Query("""
            SELECT r.filePath FROM Recording r
             WHERE r.channel.id = :channelId
               AND r.status = com.example.monitor.entity.Recording.RecordingStatus.RECORDING
            """)
    List<String> findRecordingFilePathsByChannelId(@Param("channelId") Long channelId);

    /**
     * どのチャンネルにも紐づいていない録画履歴を取得する。
     *
     * <p>URL 指定のダウンロード（{@link com.example.monitor.service.VideoDownloadService}）で
     * 取り込んだ、監視対象に登録されていないチャンネルの動画がこれにあたる。
     *
     * <p>{@link com.example.monitor.service.RecordingFileService#calculateUsage()} が、
     * 「登録中のどのチャンネル ID とも一致しないディレクトリ」のうち、チャンネルに紐づかない録画の
     * 置き場所を削除済みチャンネルと見分けて表示名を付けるために使う。
     *
     * @return チャンネルに紐づいていない録画履歴の一覧
     */
    List<Recording> findByChannelIsNull();

    /**
     * 指定した動画 ID の録画履歴が既に存在するかを判定する。
     *
     * <p>URL 指定のダウンロードが新しい動画（ほとんどの場合）を 1 回の問い合わせで通すためと、
     * 孤立ファイルの確認（{@link com.example.monitor.service.OrphanedPreviewService}）が履歴のある動画の
     * ファイルを消さないために使う。状態は問わない。ダウンロードは、履歴があれば {@link #findByVideoId} で
     * 状態を見て、失敗（{@code FAILED}）だけなら消して取り直す
     * （{@link com.example.monitor.service.VideoDownloadService#startDownload(String)}）。
     *
     * @param videoId 確認する動画 ID
     * @return 履歴が存在すれば {@code true}
     */
    boolean existsByVideoId(String videoId);

    /**
     * 指定した動画 ID の録画履歴をすべて返す。
     *
     * <p>URL 指定のダウンロード（{@link com.example.monitor.service.VideoDownloadService}）が、
     * 失敗（{@code FAILED}）の履歴だけが残っているか（消して取り直してよいか）を確かめるために使う。
     * {@link #findFirstByVideoId} の 1 件だけで判断しないのは、失敗と完了の履歴が並んでいるときに完了を見落とすと、
     * 失敗の履歴と一緒に完了した録画のファイルまで消してしまうため
     * （{@link com.example.monitor.service.RecordingFileService#deleteFile(Recording)} は、同じフォルダーにある
     * 同じ動画 ID のファイルをまとめて消す）。
     *
     * @param videoId 動画 ID
     * @return 録画履歴。無ければ空
     */
    List<Recording> findByVideoId(String videoId);

    /**
     * 指定した動画 ID の録画履歴を 1 件返す。
     *
     * <p>「端末に保存」で、既にサービスにある録画のファイルをそのまま渡すために使う
     * （{@link #existsByVideoId} だけでは渡すファイルの場所が分からない）。
     * サービスへの保存は、再生できる録画や取得中の履歴があれば取り直さず、失敗の履歴は消してから取り直すので、
     * 同じ動画 ID の履歴は通常 1 件だが、念のため先頭だけを取る。
     *
     * @param videoId 動画 ID
     * @return 録画履歴。無ければ空
     */
    Optional<Recording> findFirstByVideoId(String videoId);

    /**
     * 渡した動画 ID のうち、指定した状態の録画をまとめて返す。
     *
     * <p>YouTube の検索結果（最大 100 件）に「保存済み」の印を付けるために使う
     * （{@link #findFirstByVideoId} を 1 件ずつ呼ぶと、結果の件数だけ問い合わせが走るため）。
     *
     * @param videoIds 調べる動画 ID
     * @param statuses 対象にする状態
     * @return 該当する録画
     */
    List<Recording> findByVideoIdInAndStatusIn(Collection<String> videoIds, Collection<RecordingStatus> statuses);

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
