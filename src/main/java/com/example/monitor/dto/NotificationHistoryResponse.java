package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.NotificationHistory;
import com.example.monitor.util.StreamLinkUtils;

import java.time.LocalDateTime;

/**
 * 通知履歴 1 件を API のレスポンスとして返す形。
 *
 * <p>エンティティは遅延読み込みのチャンネル参照を持つため、そのまま返すと
 * JSON 変換時に問題が起きやすい。必要な値だけを取り出したこの型に詰め替えて返す。
 *
 * @param id               履歴の主キー
 * @param youtubeChannelId 通知対象チャンネルの YouTube チャンネル ID
 * @param channelName      通知対象チャンネルの表示名
 * @param channelUrl       チャンネルページの URL。配信元ごとに形が違う（Twitch はログイン名から作る）ため
 *                         サーバーで組み立てる。Twitch でログイン名が無ければ {@code null}
 * @param videoId          配信の動画 ID
 * @param videoUrl         配信の視聴ページの URL（画面の「動画」列のリンク先）。YouTube の通知だけ
 *                         動画 ID から組み立てる。Twitch の動画 ID は配信 ID で、視聴ページを
 *                         作れないため {@code null}（画面は動画 ID を文字で出す）。ID の形から
 *                         配信元を推測しないのは、Twitch の配信 ID（数字だけ）が 11 文字だと
 *                         YouTube の動画 ID と見分けられないため。URL を履歴に保存せず、返すたびに
 *                         組み立てるのは、列を足さずに保存済みの履歴にも同じ決まりを効かせるため
 * @param videoTitle      通知時点での配信タイトル
 * @param status           送信結果（{@code SUCCESS} または {@code FAILED}）
 * @param errorMessage     失敗理由。成功時は {@code null}
 * @param notifiedAt       送信を試みた時刻
 */
public record NotificationHistoryResponse(
        Long id,
        String youtubeChannelId,
        String channelName,
        String channelUrl,
        String videoId,
        String videoUrl,
        String videoTitle,
        String status,
        String errorMessage,
        LocalDateTime notifiedAt
) {

    /**
     * エンティティからレスポンスを組み立てる。
     *
     * @param history 変換元のエンティティ
     * @return 変換後のレスポンス
     */
    public static NotificationHistoryResponse from(NotificationHistory history) {
        MonitoredChannel channel = history.getChannel();
        return new NotificationHistoryResponse(
                history.getId(),
                channel.getYoutubeChannelId(),
                channel.getChannelName(),
                StreamLinkUtils.channelUrl(
                        channel.getPlatform(), channel.getYoutubeChannelId(), channel.getChannelLogin()),
                history.getVideoId(),
                StreamLinkUtils.videoUrl(channel.getPlatform(), history.getVideoId(), null),
                history.getVideoTitle(),
                history.getStatus().name(),
                history.getErrorMessage(),
                history.getNotifiedAt()
        );
    }
}
