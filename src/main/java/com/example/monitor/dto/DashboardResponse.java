package com.example.monitor.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ダッシュボード画面に表示する集計値をまとめたレスポンス。
 *
 * @param totalChannels        監視対象として登録されているチャンネル数
 * @param liveNowCount         そのうち現在配信中と観測されている数
 * @param liveNowChannels      現在配信中のチャンネルの一覧
 * @param notificationsLast24h 直近 24 時間に試みた通知の件数（成功・失敗の両方を含む）
 * @param notificationFailuresLast24h 直近 24 時間の通知のうち失敗した件数
 * @param detectionFailingChannels    配信状態の判定に連続で失敗しているチャンネルの一覧。
 *                                    空でなければ YouTube 側の仕様変更などで検知が壊れている疑いがある
 * @param recordingStatus      録画履歴の状態別の件数
 * @param serviceStartedAt     アプリの起動時刻
 * @param uptimeSeconds        起動してからの経過秒数
 */
public record DashboardResponse(
        long totalChannels,
        long liveNowCount,
        List<LiveChannelSummary> liveNowChannels,
        long notificationsLast24h,
        long notificationFailuresLast24h,
        List<DetectionFailureSummary> detectionFailingChannels,
        RecordingStatusSummary recordingStatus,
        LocalDateTime serviceStartedAt,
        long uptimeSeconds
) {

    /**
     * 録画履歴の状態別の件数。
     *
     * <p>ダッシュボードの内訳グラフ用。失敗が増えていることに気づけるようにするのが主目的で、
     * {@code failed} が積み上がっていれば録画の設定か {@code yt-dlp} 側に問題がある。
     *
     * @param completed 完了した録画の件数
     * @param partial   配信の途中で終わったが再生はできる録画の件数
     * @param recording 録画中の件数
     * @param failed    失敗した録画の件数
     */
    public record RecordingStatusSummary(long completed, long partial, long recording, long failed) {
    }

    /**
     * 配信状態の判定に連続失敗しているチャンネル 1 件分の要約。
     *
     * @param youtubeChannelId       YouTube が発行するチャンネル ID
     * @param channelName            表示用のチャンネル名
     * @param consecutiveFailures    連続で失敗している回数
     * @param lastDetectionSuccessAt 最後に判定できた時刻。一度も成功していなければ {@code null}
     */
    public record DetectionFailureSummary(
            String youtubeChannelId,
            String channelName,
            int consecutiveFailures,
            LocalDateTime lastDetectionSuccessAt
    ) {}

    /**
     * 現在配信中のチャンネル 1 件分の要約。
     *
     * @param youtubeChannelId YouTube が発行するチャンネル ID
     * @param channelName      表示用のチャンネル名
     * @param videoId          配信中の動画 ID
     */
    public record LiveChannelSummary(
            String youtubeChannelId,
            String channelName,
            String videoId
    ) {}
}
