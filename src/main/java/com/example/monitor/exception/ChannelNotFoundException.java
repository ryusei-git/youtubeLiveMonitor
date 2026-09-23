package com.example.monitor.exception;

/**
 * 指定された監視対象チャンネルが存在しないときに投げられる。
 *
 * <p>REST API では 404 Not Found に対応付けている。
 */
public class ChannelNotFoundException extends RuntimeException {

    /**
     * @param channelRecordId 見つからなかった監視対象の主キー
     */
    public ChannelNotFoundException(Long channelRecordId) {
        super("チャンネルが見つかりません: id=" + channelRecordId);
    }
}
