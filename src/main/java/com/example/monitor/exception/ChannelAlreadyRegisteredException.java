package com.example.monitor.exception;

/**
 * 既に監視対象として登録済みのチャンネルを、重ねて登録しようとしたときに投げられる。
 *
 * <p>「入力が不正」ではなく「状態が競合している」ことを表すため、
 * REST API では 409 Conflict に対応付けている。
 */
public class ChannelAlreadyRegisteredException extends RuntimeException {

    /**
     * @param youtubeChannelId 既に登録されている YouTube チャンネル ID
     */
    public ChannelAlreadyRegisteredException(String youtubeChannelId) {
        super("既に登録されています: " + youtubeChannelId);
    }
}
