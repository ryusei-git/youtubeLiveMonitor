package com.example.monitor.dto;

import java.util.List;

/**
 * {@code recordings/} ディレクトリの使用量集計。
 *
 * <p>DB（{@code Recording.fileSizeBytes}）の合計ではなく、実際にディスク上へ
 * 書き込まれているファイルを走査して求める。録画が失敗して DB 上は紐づく完成ファイルが
 * 無いのに、映像・音声の断片ファイルだけがディスクに残る（実際に発生した）ケースも
 * 取りこぼさないようにするため。
 *
 * @param totalBytes 合計使用量（バイト）
 * @param byChannel  チャンネルごとの内訳。使用量が多い順に並ぶ
 */
public record DiskUsageResponse(
        long totalBytes,
        List<ChannelDiskUsage> byChannel
) {

    /**
     * 1 チャンネル分の使用量。
     *
     * @param youtubeChannelId YouTube が発行するチャンネル ID（ディレクトリ名でもある）
     * @param channelName      表示用のチャンネル名。既に削除済みのチャンネルの場合は代替の表示名
     * @param bytes            このチャンネルの使用量（バイト）
     * @param registered       監視対象として現在も登録されているか。{@code false} なら
     *                         チャンネル削除後に取り残された録画ファイル（一括削除の対象）。
     *                         画面が表示名の文字列で判定せずに済むよう、真偽値で持たせている
     */
    public record ChannelDiskUsage(
            String youtubeChannelId,
            String channelName,
            long bytes,
            boolean registered
    ) {}
}
