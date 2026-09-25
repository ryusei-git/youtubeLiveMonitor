package com.example.monitor.dto;

import java.time.Instant;

/**
 * 外部動画の視聴に必要な情報だけを返し、画像本体は専用の経路で配信する。
 *
 * @param platformLabel 画面に出す配信元の名前（{@code Platform.displayName()}）。{@code platform} は絞り込みなどの値として残す
 */
public record OnlineVideoResponse(String id, Long channelId, String channelName, String platform,
        String platformLabel, String title, String watchUrl, String thumbnailUrl, Instant publishedAt,
        Instant lastObservedAt, String state, boolean playable, boolean thumbnailRetryExhausted,
        String contentKind, Instant scheduledStartTime) {}
