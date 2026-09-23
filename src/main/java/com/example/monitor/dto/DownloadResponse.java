package com.example.monitor.dto;

import com.example.monitor.platform.Platform;

/**
 * ダウンロードの受け付け結果。
 *
 * <p><b>「完了しました」ではなく「受け付けました」を表す。</b>ダウンロードは数十分かかることが
 * あるため、プロセスの起動が済んだ時点で返す。以降の進み具合は録画履歴の状態
 * （{@code RECORDING} → {@code COMPLETED} / {@code PARTIAL} / {@code FAILED}）で追う。
 *
 * <p>{@link #recordingId} を返しているのは、画面がそのまま再生ページ
 * （{@code player.html?id=◯}）へ案内できるようにするため。
 *
 * @param recordingId      作成された録画履歴の主キー
 * @param platform         この URL を担当したプラットフォーム
 * @param videoId          ダウンロード対象の動画 ID
 * @param title            動画のタイトル。取得できなかった場合は動画 ID が入る
 * @param channelId        特定できたチャンネル識別子。特定できなかった場合は {@code null}
 * @param channelName      監視対象として登録済みだった場合のチャンネル名。
 *                         未登録・特定できなかった場合は {@code null}
 *                         （録画がどのチャンネルにも紐づかなかったことを表す）
 * @param filePath         保存先（{@code monitor.recording.directory} からの相対パス）
 */
public record DownloadResponse(
        Long recordingId,
        Platform platform,
        String videoId,
        String title,
        String channelId,
        String channelName,
        String filePath
) {
}
