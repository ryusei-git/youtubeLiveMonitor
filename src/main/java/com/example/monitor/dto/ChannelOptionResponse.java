package com.example.monitor.dto;

/**
 * 選択肢に出す監視チャンネル 1 件。利用者のアーカイブの「チャンネル」の絞り込みに使う。
 *
 * <p>管理者向けの {@link MonitoredChannelResponse} を使い回さないのは、あちらが録画の設定や購読者数など
 * 管理者にだけ見せる項目を持つため。選択肢に要るのは主キーと名前だけなので、その 2 つに絞る。
 *
 * @param id          チャンネルの主キー。録画一覧の {@code channelId} に渡す
 * @param channelName チャンネル名
 */
public record ChannelOptionResponse(Long id, String channelName) {
}
