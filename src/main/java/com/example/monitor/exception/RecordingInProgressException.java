package com.example.monitor.exception;

/**
 * 録画中（{@code RECORDING}）の録画履歴を削除しようとしたときに投げられる。
 *
 * <p>{@code yt-dlp} が書き込み中のファイルを消すと、プロセス側でエラーになったり
 * 不完全なファイルが残ったりする恐れがあるため、完了・失敗が確定するまで削除を禁止している。
 * REST API では 409 Conflict に対応付けている。
 */
public class RecordingInProgressException extends RuntimeException {

    /**
     * @param recordingId 削除しようとした録画履歴の主キー
     */
    public RecordingInProgressException(Long recordingId) {
        super("録画中のため削除できません: id=" + recordingId);
    }
}
