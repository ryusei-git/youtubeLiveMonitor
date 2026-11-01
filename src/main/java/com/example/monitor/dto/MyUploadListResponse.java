package com.example.monitor.dto;

import java.util.List;

/**
 * 自分が上げた動画の一覧と、上げられる量の上限。
 *
 * <p>上限を一緒に返すのは、画面が送る前に大きさを確かめて伝えられるようにするため。
 *
 * @param maxFileBytes 1 本の大きさの上限（バイト）
 * @param quotaBytes   1 人が置ける合計の上限（バイト）
 * @param usedBytes    いま置いている合計（バイト）
 * @param items        上げた動画（新しい順）
 */
public record MyUploadListResponse(
        long maxFileBytes,
        long quotaBytes,
        long usedBytes,
        List<MyUploadResponse> items
) {
}
