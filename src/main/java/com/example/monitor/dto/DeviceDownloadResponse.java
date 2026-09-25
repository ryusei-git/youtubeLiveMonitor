package com.example.monitor.dto;

/**
 * 「端末に保存」の一時取得の状態。受け付け（{@code POST}）と状態の確認（{@code GET}）の両方で返す。
 *
 * <p>既にサービスの録画にある動画は取り直さないので、仕事を作らずに {@link Status#READY} と
 * 録画のファイルの場所を返す（{@link #jobId} は {@code null}）。画面は {@link #fileUrl} が
 * あればそれをそのまま受け取りのリンクにすればよく、2 つの場合を見分けなくて済む。
 *
 * @param jobId       仕事の ID。既にある録画を渡す場合は {@code null}
 * @param status      状態
 * @param title       動画のタイトル。取得できなかった場合は動画 ID
 * @param recordingId 既にある録画を渡す場合の録画履歴の主キー。一時取得なら {@code null}
 * @param fileUrl     受け取り先の URL。{@link Status#READY} のときだけ入る
 */
public record DeviceDownloadResponse(
        String jobId,
        Status status,
        String title,
        Long recordingId,
        String fileUrl
) {

    /** 一時取得の状態。仕事の状態はメモリだけに持つので、再起動で消える（Issue #451 の決定）。 */
    public enum Status {
        /** {@code yt-dlp} が取得中。 */
        RUNNING,
        /** 受け取れる。 */
        READY,
        /** 再生できるファイルを用意できなかった。 */
        FAILED
    }
}
