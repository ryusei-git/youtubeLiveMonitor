package com.example.monitor.dto;

/**
 * 「端末に保存」の一時取得の状態。受け付け（{@code POST}）・状態の確認（{@code GET}）・自分の仕事の一覧（{@code GET /api/my/downloads/device} の要素）で返す。
 *
 * <p>既にサービスの録画にある動画は取り直さないので、仕事を作らずに {@link Status#READY} と
 * 録画のファイルの場所を返す（{@link #jobId} は {@code null}）。画面は {@link #fileUrl} が
 * あればそれをそのまま受け取りのリンクにすればよく、2 つの場合を見分けなくて済む。
 *
 * @param jobId       仕事の ID。既にある録画を渡す場合は {@code null}
 * @param status      状態
 * @param videoId     動画 ID。画面が端末に保存するときのファイル名（タイトルが無い場合は {@code <動画ID>.mp4}）を作るのに使う。受け付けた直後（動画の情報を調べている数秒）の仕事は {@code null}
 * @param title       動画のタイトル。取得できなかった場合は動画 ID。受け付けた直後（動画の情報を調べている数秒）の仕事は {@code null}
 * @param recordingId 既にある録画を渡す場合の録画履歴の主キー。一時取得なら {@code null}
 * @param fileUrl     受け取り先の URL。{@link Status#READY} と {@link Status#PARTIAL} のときだけ入る
 */
public record DeviceDownloadResponse(
        String jobId,
        Status status,
        String videoId,
        String title,
        Long recordingId,
        String fileUrl
) {

    /** 一時取得の状態。仕事の状態はメモリだけに持つので、再起動で消える（Issue #451 の決定）。 */
    public enum Status {
        /** {@code yt-dlp} が取得中。 */
        RUNNING,
        /** 受け取れる（最後まで取得できた完成品）。 */
        READY,
        /**
         * 途中までしか取得できなかったが、そこまでを再生できる形にした（音声が無い・途中で切れていることがある）。
         * 受け取れる。サービスへの保存が同じものを録画の {@code PARTIAL} として区別しているのと合わせている。
         */
        PARTIAL,
        /** 再生できるファイルを用意できなかった。 */
        FAILED;

        /**
         * 受け取れるファイルがあるか。途中まで（{@link #PARTIAL}）も受け取れる。
         *
         * @return {@link #READY} か {@link #PARTIAL} なら {@code true}
         */
        public boolean hasFile() {
            return this == READY || this == PARTIAL;
        }
    }
}
