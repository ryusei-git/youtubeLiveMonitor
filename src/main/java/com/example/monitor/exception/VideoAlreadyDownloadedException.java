package com.example.monitor.exception;

/**
 * 既に録画履歴がある動画を、重ねてダウンロードしようとしたときに投げられる。
 *
 * <p>「入力が不正」ではなく「状態が競合している」ことを表すため、
 * REST API では 409 Conflict に対応付けている
 * （{@link ChannelAlreadyRegisteredException} と同じ考え方）。
 *
 * <p>判定は録画の状態を問わない。失敗した履歴が残っている動画をもう一度取り込みたい場合は、
 * <b>先にその履歴を削除してもらう</b>。同じ動画 ID のファイルを同じ場所に二重に書き込むと、
 * 一方が他方の出力を壊すため。
 */
public class VideoAlreadyDownloadedException extends RuntimeException {

    /**
     * @param videoId 既に録画履歴がある動画 ID
     */
    public VideoAlreadyDownloadedException(String videoId) {
        super("この動画は既に録画・ダウンロード済みです（取り直す場合は先に録画履歴を削除してください）: " + videoId);
    }
}
