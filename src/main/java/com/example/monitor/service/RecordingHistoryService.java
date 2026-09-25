package com.example.monitor.service;

import com.example.monitor.dto.DashboardResponse.RecordingStatusSummary;
import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.dto.RecordingGenreCountResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.entity.RecordingMark;
import com.example.monitor.exception.RecordingInProgressException;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.RecordingMarkRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.DatabaseUpdateVerifier;
import com.example.monitor.util.RequestContext;
import com.example.monitor.util.TitleGenreExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 録画履歴の記録と照会を担当する。
 *
 * <p>{@link StreamRecorder} が録画の開始・完了・失敗のタイミングでこのクラスを呼び出す。
 * 完了・失敗は録画プロセスの終了を待つ仮想スレッドから非同期に呼ばれるため、
 * このクラス自身は呼び出し元のスレッドを意識せず、渡された ID に対して淡々と更新するだけでよい。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingHistoryService {

    private final RecordingRepository recordingRepository;
    private final RecordingFileService recordingFileService;
    private final AppUserRepository appUserRepository;
    private final RecordingMarkRepository recordingMarkRepository;
    private final AuditLogger auditLogger;

    /**
     * 録画の開始を記録する。
     *
     * <p>{@code channel} には {@code null} を渡せる。URL 指定のダウンロード
     * （{@link VideoDownloadService}）では、監視対象に登録されていないチャンネルの動画が
     * 対象になりうるため（{@link com.example.monitor.entity.Recording#channel} の JavaDoc 参照）。
     *
     * @param channel    録画対象のチャンネル。紐づくチャンネルが無い場合は {@code null}
     * @param videoId    録画対象の配信の動画 ID
     * @param videoTitle 録画開始時点での配信タイトル
     * @param filePath   録画ファイルの保存先パス（{@code monitor.recording.directory}からの相対パス）
     * @return 保存された録画履歴（以降の完了・失敗の記録にはこの{@code id}を使う）
     */
    public Recording recordStart(MonitoredChannel channel, String videoId, String videoTitle, String filePath) {
        Recording recording = Recording.builder()
                .channel(channel)
                .videoId(videoId)
                .videoTitle(videoTitle)
                .genre(TitleGenreExtractor.extract(videoTitle))
                .filePath(filePath)
                .status(RecordingStatus.RECORDING)
                .build();
        return recordingRepository.save(recording);
    }

    /**
     * 録画の正常完了を記録する。
     *
     * @param recordingId   {@link #recordStart}で発行された録画履歴の主キー
     * @param fileSizeBytes 完成したファイルのサイズ（バイト）
     */
    public void markCompleted(Long recordingId, long fileSizeBytes) {
        DatabaseUpdateVerifier.verify(
                recordingRepository.markCompleted(recordingId, fileSizeBytes, LocalDateTime.now()),
                "録画の完了記録", recordingId);
    }

    /**
     * 配信の途中で終わった録画を記録する。そこまでの内容は再生できる状態になっている。
     *
     * @param recordingId   {@link #recordStart}で発行された録画履歴の主キー
     * @param fileSizeBytes 再生できる状態に直したファイルのサイズ（バイト）
     */
    public void markPartial(Long recordingId, long fileSizeBytes) {
        DatabaseUpdateVerifier.verify(
                recordingRepository.markPartial(recordingId, fileSizeBytes, LocalDateTime.now()),
                "途中までの録画の記録", recordingId);
    }

    /**
     * 録画の失敗を記録する。
     *
     * @param recordingId {@link #recordStart}で発行された録画履歴の主キー
     */
    public void markFailed(Long recordingId) {
        DatabaseUpdateVerifier.verify(
                recordingRepository.markFailed(recordingId, LocalDateTime.now()),
                "録画の失敗記録", recordingId);
    }

    /**
     * 全チャンネルの録画履歴を新しい順に取得する。
     *
     * @param pageable ページ指定
     * @return 開始時刻の降順に並んだ履歴
     */
    public Page<Recording> findRecent(Pageable pageable) {
        return recordingRepository.findAllByOrderByStartedAtDesc(pageable);
    }

    /**
     * 録画履歴を条件で絞り込んで取得する。どの条件も省略でき、組み合わせられる。
     *
     * <p>期間は日付で受け、{@code to} の翌日 0 時より前までを含める。利用者が
     * 「9/1〜9/30」と指定したとき、9/30 の配信も入ってほしいため。
     *
     * <p>視聴済み・お気に入りは {@code username} の利用者の印だけを見る。印は利用者ごとの
     * ものなので、他人の印で絞り込まれると一覧の意味が変わってしまうため。
     *
     * @param username  印を見る利用者のログイン名
     * @param channelId 監視対象チャンネルの主キー。{@code null} なら絞り込まない
     * @param keyword   検索キーワード（配信タイトル・チャンネル名の部分一致）。
     *                  {@code null} や空文字なら絞り込まない
     * @param status    絞り込む状態。{@code null} なら絞り込まない
     * @param from      開始日（この日を含む）。{@code null} なら絞り込まない
     * @param to        終了日（この日を含む）。{@code null} なら絞り込まない
     * @param genre     ジャンル（完全一致）。{@code null} や空文字なら絞り込まない
     * @param watched   {@code true} なら視聴済みだけ、{@code false} なら未視聴だけ。{@code null} なら絞り込まない
     * @param favoriteOnly お気に入りだけに絞るか
     * @param pageable  ページ指定と並び順
     * @return 条件に一致する録画履歴
     */
    public Page<Recording> search(String username, Long channelId, String keyword, RecordingStatus status,
                                  LocalDate from, LocalDate to, String genre,
                                  Boolean watched, boolean favoriteOnly, Pageable pageable) {
        return search(username, false, channelId, keyword, status, from, to, genre,
                watched, favoriteOnly, false, pageable);
    }

    /**
     * 録画履歴を条件で絞り込んで取得する。購読の限定と再生可能の絞り込みも指定できる版。
     *
     * <p>利用者の画面（{@code /api/my/recordings}）は再生できる録画に絞って検索する。
     * 管理者と同じクエリを通すことで、絞り込み条件の食い違いを作らない。
     *
     * <p><b>購読に限るとき、利用者が見つからなければ空にする。</b>{@code userId} が {@code null} だと
     * 購読の条件が「誰の購読か」を失うため、何も返さないのが安全側になる。
     *
     * @param username       印を見る利用者（購読に限るときは購読の持ち主）のログイン名
     * @param subscribedOnly 購読しているチャンネルの録画だけに絞るか
     * @param channelId      監視対象チャンネルの主キー。{@code null} なら絞り込まない
     * @param keyword        検索キーワード。{@code null} や空文字なら絞り込まない
     * @param status         絞り込む状態。{@code null} なら絞り込まない
     * @param from           開始日（この日を含む）。{@code null} なら絞り込まない
     * @param to             終了日（この日を含む）。{@code null} なら絞り込まない
     * @param genre          ジャンル（完全一致）。{@code null} や空文字なら絞り込まない
     * @param watched        {@code true} なら視聴済みだけ、{@code false} なら未視聴だけ。{@code null} なら絞り込まない
     * @param favoriteOnly   お気に入りだけに絞るか
     * @param playableOnly   再生できる録画（完了・途中まで）だけに絞るか
     * @param pageable       ページ指定と並び順
     * @return 条件に一致する録画履歴
     */
    public Page<Recording> search(String username, boolean subscribedOnly, Long channelId, String keyword,
                                  RecordingStatus status, LocalDate from, LocalDate to, String genre,
                                  Boolean watched, boolean favoriteOnly, boolean playableOnly,
                                  Pageable pageable) {
        Long userId = findUserId(username);
        if (subscribedOnly && userId == null) {
            return Page.empty(pageable);
        }
        // 空文字はクエリ側で「条件なし」と区別できないため、ここで null に寄せる
        return recordingRepository.search(userId, subscribedOnly, channelId, blankToNull(keyword), status,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                blankToNull(genre), watched, favoriteOnly, playableOnly, pageable);
    }

    /**
     * 利用者が録画群に付けた印を、録画の主キーをキーにしてまとめて取得する。
     *
     * <p>一覧の 1 ページ分を 1 回のクエリで引くためのもの（行ごとに引くと件数ぶんクエリが飛ぶ）。
     *
     * @param username     印を見る利用者のログイン名
     * @param recordingIds 録画の主キーの一覧
     * @return 録画の主キー → 印。印の無い録画はキーに含まない
     */
    public Map<Long, RecordingMark> findMarks(String username, Collection<Long> recordingIds) {
        Long userId = findUserId(username);
        if (userId == null || recordingIds.isEmpty()) {
            return Map.of();
        }
        return recordingMarkRepository.findByUser_IdAndRecording_IdIn(userId, recordingIds).stream()
                .collect(Collectors.toMap(mark -> mark.getRecording().getId(), Function.identity()));
    }

    /**
     * ログイン名から利用者の主キーを引く。見つからなければ {@code null}（印が無いものとして扱われる）。
     *
     * @param username ログイン名
     * @return 利用者の主キー
     */
    private Long findUserId(String username) {
        return username == null ? null
                : appUserRepository.findByUsername(username).map(AppUser::getId).orElse(null);
    }

    /**
     * ジャンルごとの録画件数を、件数の多い順に取得する。
     *
     * <p>失敗・録画中の録画も数える。管理画面の一覧は状態でも絞り込めて、状態を問わず全部を出すため。
     *
     * @return ジャンルと件数の一覧
     */
    public List<RecordingGenreCountResponse> countByGenre() {
        return recordingRepository.countByGenre(null, false);
    }

    /**
     * 再生できる録画に限って、ジャンルごとの件数を数える。利用者のアーカイブのジャンルの選択肢に使う。
     *
     * <p><b>再生できる録画（完了・途中まで）だけを数える。</b>利用者のアーカイブは再生できる録画だけを
     * 一覧に出すため、失敗・録画中まで数えると選択肢の件数が一覧の件数より多くなる（#228）。
     *
     * @return ジャンルと件数の一覧
     */
    public List<RecordingGenreCountResponse> countPlayableByGenre() {
        return recordingRepository.countByGenre(null, true);
    }

    /**
     * 空白だけの文字列を {@code null} に、それ以外は前後の空白を除いて返す。
     *
     * @param value 入力値
     * @return 正規化した値
     */
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * 録画履歴を 1 件取得する。再生画面が対象を特定するために使う。
     *
     * @param recordingId 録画履歴の主キー
     * @return 該当する録画履歴
     * @throws RecordingNotFoundException 指定 ID の録画履歴が存在しない場合
     */
    public Recording findById(Long recordingId) {
        return recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
    }

    /**
     * 録画履歴と、それに紐づく録画ファイルを削除する。
     *
     * <p>DB からの削除を先に行い、ファイルの削除はそれに続く後始末として扱う
     * （{@code MonitoredChannelService.remove()} が通知履歴の連鎖削除の後にログファイルを
     * 削除するのと同じ順序）。ファイル削除に失敗しても、履歴が一覧から消えるという
     * 利用者から見た結果は変わらないため、この呼び出し自体は失敗にしない。
     *
     * @param recordingId 削除対象の録画履歴の主キー
     * @throws RecordingNotFoundException  指定 ID の録画履歴が存在しない場合
     * @throws RecordingInProgressException 録画中（{@code RECORDING}）の場合
     */
    public void deleteRecording(Long recordingId) {
        Recording recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));

        if (recording.getStatus() == RecordingStatus.RECORDING) {
            throw new RecordingInProgressException(recordingId);
        }

        recordingRepository.deleteById(recordingId);
        recordingFileService.deleteFile(recording);
        log.info("録画履歴を削除しました: id={}, video={}", recordingId, recording.getVideoId());
        recordAction(AuditAction.RECORDING_DELETE, recordingId,
                "video=" + recording.getVideoId() + ", title=" + recording.getVideoTitle());
    }

    /**
     * 操作者を解決して録画関連の監査ログへ記録する。
     *
     * <p>{@code MonitoredChannelService.recordChannelAction()} と同じ考え方。CLI からも
     * 呼ばれうるため {@link RequestContext#currentUsername()} が {@code null} のときは
     * 利用者情報を空欄のまま記録する。
     *
     * @param action      操作の種別
     * @param recordingId 対象録画履歴の主キー
     * @param detail      補足情報
     */
    private void recordAction(AuditAction action, Long recordingId, String detail) {
        String username = RequestContext.currentUsername();
        Long userId = username == null ? null
                : appUserRepository.findByUsername(username).map(AppUser::getId).orElse(null);
        auditLogger.record(action, AuditOutcome.SUCCESS, userId, username, null,
                "RECORDING", String.valueOf(recordingId), detail);
    }

    /**
     * 録画履歴の状態別の件数を数える。
     *
     * @return 完了・途中まで・録画中・失敗のそれぞれの件数
     */
    public RecordingStatusSummary countByStatus() {
        return new RecordingStatusSummary(
                recordingRepository.countByStatus(RecordingStatus.COMPLETED),
                recordingRepository.countByStatus(RecordingStatus.PARTIAL),
                recordingRepository.countByStatus(RecordingStatus.RECORDING),
                recordingRepository.countByStatus(RecordingStatus.FAILED));
    }

    /**
     * {@code recordings/} ディレクトリの使用量を、チャンネル別に集計する。
     *
     * @return 合計使用量とチャンネル別の内訳
     */
    public DiskUsageResponse calculateDiskUsage() {
        return recordingFileService.calculateUsage();
    }

    /**
     * 一覧表示用の付加情報（再生時間・サムネイル）を記録する。
     *
     * @param recordingId     録画履歴の主キー
     * @param durationSeconds 再生時間（秒）。読み取れなければ {@code null}
     * @param thumbnailPath   サムネイル画像の相対パス。生成できなければ {@code null}
     */
    public void updateMediaMetadata(Long recordingId, Integer durationSeconds, String thumbnailPath) {
        DatabaseUpdateVerifier.verify(
                recordingRepository.updateMediaMetadata(recordingId, durationSeconds, thumbnailPath),
                "再生時間・サムネイルの記録", recordingId);
    }
}
