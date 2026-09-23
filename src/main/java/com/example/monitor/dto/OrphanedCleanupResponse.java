package com.example.monitor.dto;

import java.util.List;

/**
 * 孤立した録画ファイルを一括削除した結果。
 *
 * <p>対象は2種類（{@link com.example.monitor.service.RecordingFileService#deleteOrphanedRecordings()}
 * 参照）。{@code deletedFiles}・{@code freedBytes} はどちらの対象分も合算した値になる。
 *
 * @param deletedChannels 丸ごと削除した「削除済みチャンネル」のディレクトリ数
 * @param deletedFiles    削除したファイルの数（削除済みチャンネル分＋登録中チャンネルの孤立断片分）
 * @param freedBytes      解放された容量（バイト）
 * @param skippedChannels 録画がまだ進行中のため削除を見送ったチャンネル ID の一覧。
 *                        チャンネルを削除しても {@code yt-dlp} は動き続けるため、
 *                        書き込み中のファイルを消さないよう対象から外したもの
 */
public record OrphanedCleanupResponse(
        int deletedChannels,
        int deletedFiles,
        long freedBytes,
        List<String> skippedChannels
) {}
