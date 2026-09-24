package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.Platform;
import com.example.monitor.util.StreamLinkUtils;
import com.example.monitor.util.TitleGenreExtractor;
import com.example.monitor.util.YouTubeWatchUrl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

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
 * @param channelUrl チャンネルページの URL。組み立てられない（YouTube 以外）場合は {@code null}
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
        String channelIconUrl,
        String channelUrl
) {

    /** 配信予定として返す範囲（今からどれだけ先の開始予定までか）。 */
    private static final Duration WINDOW = Duration.ofDays(7);

    /**
     * チャンネルに記録された配信予定のうち、返すものを開始予定時刻の早い順に並べる。
     *
     * <p>返すのは開始予定が「今から 7 日後」（{@code WINDOW}）より前の予定だけ。
     * 何か月も先のフリーチャット枠などが並ぶと、直近の予定が埋もれるため。
     * 開始予定を過ぎてもまだ始まっていない待機所（遅れている配信）は、すぐ始まる可能性が高いので含める。
     * 開始予定時刻を取得できなかった予定は、範囲内か判断できないので除く。
     *
     * <p>管理者の一覧（全チャンネル）と利用者の一覧（購読しているチャンネルだけ）で
     * 選び方と並びが食い違わないよう、判断をここ 1 か所に置いている。
     *
     * @param channels 対象のチャンネル
     * @return 配信予定の一覧
     */
    public static List<UpcomingStreamResponse> listWithinWindow(Collection<MonitoredChannel> channels) {
        LocalDateTime windowEnd = LocalDateTime.now().plus(WINDOW);
        return channels.stream()
                .filter(channel -> channel.getUpcomingVideoId() != null)
                .filter(channel -> channel.getUpcomingScheduledStartTime() != null
                        && channel.getUpcomingScheduledStartTime().isBefore(windowEnd))
                .map(UpcomingStreamResponse::from)
                .sorted(Comparator.comparing(UpcomingStreamResponse::scheduledStartTime))
                .toList();
    }

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
                channel.getChannelIconUrl(),
                StreamLinkUtils.channelUrl(channel.getPlatform(), channel.getYoutubeChannelId()));
    }
}
