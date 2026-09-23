package com.example.monitor.exception;

/**
 * 指定された録画履歴が存在しないときに投げられる。
 *
 * <p>REST API では 404 Not Found に対応付けている。
 */
public class RecordingNotFoundException extends RuntimeException {

    /**
     * @param recordingId 見つからなかった録画履歴の主キー
     */
    public RecordingNotFoundException(Long recordingId) {
        super("録画履歴が見つかりません: id=" + recordingId);
    }
}
