package com.example.monitor.service;

import com.example.monitor.dto.DashboardResponse.RecordingStatusSummary;
import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.dto.OrphanedCleanupResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.exception.RecordingInProgressException;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.DatabaseUpdateVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
    private final MonitoredChannelRepository monitoredChannelRepository;
    private final RecordingFileService recordingFileService;

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
     * 特定チャンネルの録画履歴を新しい順に取得する。
     *
     * @param channelRecordId 監視対象チャンネルの主キー（YouTube のチャンネル ID ではない）
     * @param pageable        ページ指定
     * @return 開始時刻の降順に並んだ履歴
     * @throws ChannelNotFoundException 指定 ID のチャンネルが存在しない場合
     */
    public Page<Recording> findByChannel(Long channelRecordId, Pageable pageable) {
        MonitoredChannel channel = monitoredChannelRepository.findById(channelRecordId)
                .orElseThrow(() -> new ChannelNotFoundException(channelRecordId));
        return recordingRepository.findByChannelOrderByStartedAtDesc(channel, pageable);
    }

    /**
     * 録画履歴をキーワードと状態で絞り込んで新しい順に取得する。
     *
     * @param keyword  検索キーワード（配信タイトル・チャンネル名の部分一致）。
     *                 {@code null} や空文字なら絞り込まない
     * @param status   絞り込む状態。{@code null} なら絞り込まない
     * @param pageable ページ指定
     * @return 開始時刻の降順に並んだ履歴
     */
    public Page<Recording> search(String keyword, RecordingStatus status, Pageable pageable) {
        // 空文字はクエリ側で「条件なし」と区別できないため、ここで null に寄せる
        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return recordingRepository.search(normalizedKeyword, status, pageable);
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
     * 監視対象から削除済みのチャンネルの録画ファイルをまとめて削除する。
     *
     * <p>DB 上の録画履歴はチャンネル削除時に連鎖削除で既に消えているため、
     * ここで消えるのはディスク上のファイルだけ。
     *
     * @return 削除結果の集計
     */
    public OrphanedCleanupResponse deleteOrphanedRecordings() {
        return recordingFileService.deleteOrphanedRecordings();
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
