package com.example.monitor.dto;

import java.util.List;

/**
 * 孤立した録画ファイルを一括削除した結果。
 *
 * <p>{@link com.example.monitor.service.OrphanedPreviewService#deleteConfirmed(String)} が返す。
 * 削除はファイル単位で、削除候補の確認（プレビュー）のあとに状態が変わったファイルは消さずに見送る。
 *
 * @param deletedChannels ファイルを 1 つ以上削除したディレクトリ（チャンネル ID）の数
 * @param deletedFiles    削除したファイルの数
 * @param freedBytes      解放された容量（バイト）
 * @param skippedChannels 削除を見送ったもの（ファイルの相対パスと理由）。録画中・確認後に状態が
 *                        変わった・削除に失敗した、のいずれか。{@code yt-dlp} はチャンネルを削除しても
 *                        動き続けるため、書き込み中のファイルを消さないよう対象から外す
 */
public record OrphanedCleanupResponse(
        int deletedChannels,
        int deletedFiles,
        long freedBytes,
        List<String> skippedChannels
) {}
