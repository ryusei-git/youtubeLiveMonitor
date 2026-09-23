package com.example.monitor.controller;

import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.dto.OrphanedCleanupResponse;
import com.example.monitor.dto.PageResponse;
import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.service.RecordingHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 録画履歴を参照する REST API。
 *
 * <p>実際の動画ファイルは REST API ではなく静的リソースとして配信する
 * （{@link com.example.monitor.config.RecordingResourceConfig}参照）。ここで返す
 * {@link RecordingResponse#filePath()} を {@code /recordings/} と連結すれば再生用 URL になる。
 */
@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class RecordingController {

    private final RecordingHistoryService recordingHistoryService;

    /**
     * 録画履歴を新しい順に取得する。
     *
     * <p>絞り込み条件は 3 種類あるが、{@code channelId} はチャンネル詳細からの導線専用で、
     * キーワード・状態は一覧画面の検索欄から来る。両者が同時に指定されることはないため、
     * {@code channelId} を優先する単純な分岐にしている。
     *
     * @param channelId 特定チャンネルに絞り込む場合はその主キー。省略すると全チャンネルが対象
     * @param keyword   配信タイトル・チャンネル名に対する部分一致の検索キーワード
     * @param status    絞り込む録画状態（{@code RECORDING} / {@code COMPLETED} / {@code FAILED}）
     * @param page      ページ番号（0 始まり）
     * @param size      1 ページあたりの件数
     * @return 開始時刻の降順に並んだ履歴
     */
    @GetMapping
    public PageResponse<RecordingResponse> getRecordings(
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) RecordingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(page, size);

        Page<Recording> recordings = (channelId != null)
                ? recordingHistoryService.findByChannel(channelId, pageRequest)
                : recordingHistoryService.search(keyword, status, pageRequest);

        // Page をそのまま返すと JSON 構造が Spring の実装依存になる（PageResponse の JavaDoc 参照）
        return PageResponse.from(recordings.map(RecordingResponse::from));
    }

    /**
     * 録画履歴を 1 件取得する。再生画面（{@code player.html}）が対象の情報を得るために使う。
     *
     * @param id 録画履歴の主キー
     * @return 該当する録画履歴
     * @throws com.example.monitor.exception.RecordingNotFoundException 指定 ID が存在しない場合（404）
     */
    @GetMapping("/{id}")
    public RecordingResponse getRecording(@PathVariable Long id) {
        return RecordingResponse.from(recordingHistoryService.findById(id));
    }

    /**
     * 録画履歴と、それに紐づく録画ファイルを削除する。
     *
     * @param id 削除対象の録画履歴の主キー
     * @return 本文なしの HTTP 204
     * @throws com.example.monitor.exception.RecordingNotFoundException   指定 ID が存在しない場合（404）
     * @throws com.example.monitor.exception.RecordingInProgressException 録画中の場合（409）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRecording(@PathVariable Long id) {
        recordingHistoryService.deleteRecording(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code recordings/} ディレクトリの使用量を、チャンネル別に取得する。
     *
     * @return 合計使用量とチャンネル別の内訳
     */
    @GetMapping("/disk-usage")
    public DiskUsageResponse getDiskUsage() {
        return recordingHistoryService.calculateDiskUsage();
    }

    /**
     * 監視対象から削除済みのチャンネルの録画ファイルをまとめて削除する。
     *
     * <p>チャンネルを削除しても録画ファイル本体はディスクに残る設計のため、
     * それらをまとめて片付けるための操作。<b>ファイルは元に戻せない。</b>
     * 録画がまだ進行中のチャンネルは対象から外れる（結果の {@code skippedChannels} に入る）。
     *
     * @return 削除したチャンネル数・ファイル数・解放された容量
     */
    @DeleteMapping("/orphaned")
    public OrphanedCleanupResponse deleteOrphanedRecordings() {
        return recordingHistoryService.deleteOrphanedRecordings();
    }
}
