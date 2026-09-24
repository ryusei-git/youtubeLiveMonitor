package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.Platform;
import com.example.monitor.util.TitleGenreExtractor;
import com.example.monitor.util.YouTubeWatchUrl;

import java.time.LocalDateTime;

/**
 * チャンネルに記録された配信予定を API のレスポンスとして返す形。
 *
 * <p>エンティティをそのまま返さずこの型に詰め替えるのは、DB の変更が API の仕様へ
 * 直接影響しないようにするため。
 *
 * @param channelId チャンネルの主キー
 * @param channelName 表示用のチャンネル名
 * @param platform 配信プラットフォーム
 * @param platformLabel 画面に表示するプラットフォーム名
 * @param videoId 配信予定の動画 ID
 * @param title 配信タイトル。取得できなかった場合は {@code null}
 * @param scheduledStartTime 開始予定時刻。取得できなかった場合は {@code null}
 * @param watchUrl 配信予定の視聴 URL
 * @param genre タイトルの最初の {@code 【】} から求めたジャンル。求められない場合は {@code null}
 * @param channelIconUrl チャンネルのアイコン URL。まだ読み取れていない場合は {@code null}
 */
public record UpcomingStreamResponse(
        Long channelId,
        String channelName,
        Platform platform,
        String platformLabel,
        String videoId,
        String title,
        LocalDateTime scheduledStartTime,
        String watchUrl,
        String genre,
        String channelIconUrl
) {

    /**
     * エンティティから配信予定のレスポンスを組み立てる。
     *
     * <p>配信予定は YouTube の待機所からしか記録しない（Twitch は対象外、#31）ため、
     * 視聴 URL は YouTube の形式に決まる。
     *
     * @param channel 変換元のエンティティ
     * @return 変換後のレスポンス
     */
    public static UpcomingStreamResponse from(MonitoredChannel channel) {
        return new UpcomingStreamResponse(
                channel.getId(),
                channel.getChannelName(),
                channel.getPlatform(),
                channel.getPlatform().displayName(),
                channel.getUpcomingVideoId(),
                channel.getUpcomingTitle(),
                channel.getUpcomingScheduledStartTime(),
                YouTubeWatchUrl.of(channel.getUpcomingVideoId()),
                TitleGenreExtractor.extract(channel.getUpcomingTitle()),
                channel.getChannelIconUrl());
    }
}
