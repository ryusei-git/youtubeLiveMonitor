package com.example.monitor.dto;

/**
 * チャンネル名検索の結果 1 件。
 *
 * <p>監視対象に登録したいチャンネルの ID が分からないときに、名前から候補を探すために使う。
 *
 * @param youtubeChannelId YouTube が発行するチャンネル ID。この値を監視対象の登録に使う
 * @param channelTitle     YouTube 上のチャンネル名
 * @param thumbnailUrl     チャンネルのアイコン画像 URL
 */
public record ChannelSearchResult(
        String youtubeChannelId,
        String channelTitle,
        String thumbnailUrl
) {}
