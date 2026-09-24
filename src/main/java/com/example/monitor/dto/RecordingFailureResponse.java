package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.util.StreamLinkUtils;

import java.time.LocalDateTime;

/**
 * ダッシュボードに出す、録画に失敗した配信 1 件。
 *
 * <p>{@link RecordingResponse} を流用しないのは、ファイルパスや印など失敗した録画には意味の無い
 * 項目が多く、逆に元の配信へのリンク（{@code videoUrl}）はあちらに無いため。
 *
 * @param recordingId 録画履歴の主キー
 * @param channelName チャンネル名。チャンネルに紐づかない録画は {@link RecordingResponse#UNLINKED_CHANNEL_NAME}
 * @param channelUrl  チャンネルページの URL。組み立てられなければ {@code null}
 * @param videoTitle  配信タイトル。取れていなければ {@code null}
 * @param videoUrl    配信の視聴ページの URL。組み立てられなければ {@code null}
 * @param startedAt   録画を始めた時刻
 */
public record RecordingFailureResponse(
        Long recordingId,
        String channelName,
        String channelUrl,
        String videoTitle,
        String videoUrl,
        LocalDateTime startedAt
) {

    /**
     * エンティティからレスポンスを組み立てる。
     *
     * <p>チャンネルに紐づかない録画はどのプラットフォームの動画か分からないため、
     * 動画 URL も推測しない（{@link StreamLinkUtils} の方針。録画は元の URL を保存していない）。
     *
     * @param recording 変換元のエンティティ
     * @return 変換後のレスポンス
     */
    public static RecordingFailureResponse from(Recording recording) {
        MonitoredChannel channel = recording.getChannel();
        return new RecordingFailureResponse(
                recording.getId(),
                channel == null ? RecordingResponse.UNLINKED_CHANNEL_NAME : channel.getChannelName(),
                channel == null ? null : StreamLinkUtils.channelUrl(
                        channel.getPlatform(), channel.getYoutubeChannelId(), channel.getChannelLogin()),
                recording.getVideoTitle(),
                channel == null ? null : StreamLinkUtils.videoUrl(channel.getPlatform(), recording.getVideoId(), null),
                recording.getStartedAt()
        );
    }
}
