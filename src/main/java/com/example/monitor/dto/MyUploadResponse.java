package com.example.monitor.dto;

import com.example.monitor.entity.Recording;

import java.time.LocalDateTime;

/**
 * 利用者が上げた動画 1 本を API のレスポンスとして返す形。
 *
 * <p>エンティティを直接返さない（{@code docs/pitfalls.md}「エンティティを API に直接返さない」）。
 * ログイン名（{@link Recording#uploadedBy}）は載せない。
 *
 * @param id              録画の主キー。再生は録画と同じ API・画面で行う
 * @param title           題名
 * @param fileSizeBytes   取り込んだファイルのサイズ（バイト）。上げたファイルの大きさとは少し違う
 * @param durationSeconds 再生時間（秒）。読み取れていなければ {@code null}
 * @param uploadedAt      取り込んだ時刻
 */
public record MyUploadResponse(
        Long id,
        String title,
        Long fileSizeBytes,
        Integer durationSeconds,
        LocalDateTime uploadedAt
) {

    /**
     * エンティティからレスポンスを組み立てる。
     *
     * @param recording 変換元のエンティティ
     * @return 変換後のレスポンス
     */
    public static MyUploadResponse from(Recording recording) {
        return new MyUploadResponse(
                recording.getId(),
                recording.getVideoTitle(),
                recording.getFileSizeBytes(),
                recording.getDurationSeconds(),
                recording.getCompletedAt());
    }
}
