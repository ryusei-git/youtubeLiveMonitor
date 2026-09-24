package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.Platform;
import com.example.monitor.util.StreamLinkUtils;

import java.time.LocalDateTime;

/**
 * 監視対象チャンネル 1 件を API のレスポンスとして返す形。
 *
 * <p>エンティティをそのまま返さずこの型に詰め替えるのは、
 * DB の都合（カラム構成の変更など）が API の仕様に直接影響しないようにするため。
 *
 * @param id                  監視対象の主キー。削除などの操作で指定する
 * @param platform            どのプラットフォームのチャンネルか（{@code YOUTUBE} / {@code TWITCH}）
 * @param platformLabel       画面に出すプラットフォームの表示名。画面側で対応表を持たずに済むよう
 *                            サーバーから渡す（プラットフォームを増やしたときに画面を直し忘れないため）
 * @param youtubeChannelId    プラットフォームが発行するチャンネル識別子。項目名は YouTube 由来だが、
 *                            Twitch の場合はユーザー ID（数値）が入る
 * @param channelName         表示用のチャンネル名
 * @param lastNotifiedVideoId 最後に通知した配信の動画 ID。未通知なら {@code null}
 * @param currentlyLive       直近の監視で配信中と判定されたか
 * @param currentLiveVideoId  配信中の動画 ID。配信していなければ {@code null}
 * @param lastCheckedAt       直近に監視した時刻
 * @param recordEnabled       配信を検知した際に自動録画するか
 * @param lastRecordedVideoId 最後に録画を開始した配信の動画 ID。未録画なら {@code null}
 * @param recordTitleKeywords 録画対象を絞り込むタイトルキーワード（カンマ区切り）。未設定なら {@code null}
 * @param consecutiveDetectionFailures 配信状態の判定に連続失敗している回数。0 なら正常
 * @param lastDetectionSuccessAt       最後に判定できた時刻。一度も成功していなければ {@code null}
 * @param createdAt           監視対象として登録した時刻
 * @param recordingCount      再生できる録画の件数（状態が {@code COMPLETED} と {@code PARTIAL} のもの）。
 *                            録画中・失敗は見られる録画ではないため数えない
 * @param channelUrl          チャンネルページの URL。Twitch のように ID から組み立てられない場合は {@code null}
 */
public record MonitoredChannelResponse(
        Long id,
        Platform platform,
        String platformLabel,
        String youtubeChannelId,
        String channelName,
        String lastNotifiedVideoId,
        boolean currentlyLive,
        String currentLiveVideoId,
        LocalDateTime lastCheckedAt,
        boolean recordEnabled,
        String lastRecordedVideoId,
        String recordTitleKeywords,
        int consecutiveDetectionFailures,
        LocalDateTime lastDetectionSuccessAt,
        LocalDateTime createdAt,
        long recordingCount,
        String channelUrl
) {

    /**
     * エンティティからレスポンスを組み立てる。録画件数は 0 とする。
     *
     * <p>登録直後の応答で使う。登録したばかりのチャンネルには録画が無いため、件数を数えずに済ませている。
     *
     * @param channel 変換元のエンティティ
     * @return 変換後のレスポンス
     */
    public static MonitoredChannelResponse from(MonitoredChannel channel) {
        return from(channel, 0L);
    }

    /**
     * エンティティと録画件数からレスポンスを組み立てる。
     *
     * @param channel        変換元のエンティティ
     * @param recordingCount 再生できる録画の件数
     * @return 変換後のレスポンス
     */
    public static MonitoredChannelResponse from(MonitoredChannel channel, long recordingCount) {
        return new MonitoredChannelResponse(
                channel.getId(),
                channel.getPlatform(),
                channel.getPlatform().displayName(),
                channel.getYoutubeChannelId(),
                channel.getChannelName(),
                channel.getLastNotifiedVideoId(),
                channel.isCurrentlyLive(),
                channel.getCurrentLiveVideoId(),
                channel.getLastCheckedAt(),
                channel.isRecordEnabled(),
                channel.getLastRecordedVideoId(),
                channel.getRecordTitleKeywords(),
                channel.getConsecutiveDetectionFailures(),
                channel.getLastDetectionSuccessAt(),
                channel.getCreatedAt(),
                recordingCount,
                StreamLinkUtils.channelUrl(channel.getPlatform(), channel.getYoutubeChannelId())
        );
    }
}
