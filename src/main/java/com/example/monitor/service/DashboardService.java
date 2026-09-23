package com.example.monitor.service;

import com.example.monitor.dto.DashboardResponse;
import com.example.monitor.dto.DashboardResponse.DetectionFailureSummary;
import com.example.monitor.dto.DashboardResponse.LiveChannelSummary;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.repository.MonitoredChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * ダッシュボードに表示する集計値を組み立てる。
 *
 * <p>配信中かどうかは各チャンネルに保存された「直近の監視結果」を読むだけで、
 * ここから YouTube へ問い合わせることはしない。画面を開くたびに外部通信が走るのを避けるため、
 * 実際の観測は監視ループ（{@link com.example.monitor.scheduler.LiveStreamPollingScheduler}）に任せている。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 「最近の通知件数」として集計する時間の幅。 */
    private static final int RECENT_NOTIFICATION_WINDOW_HOURS = 24;

    /**
     * 何回連続で判定に失敗したらダッシュボードに警告として出すか。
     *
     * <p>1 回だけの失敗は一時的な通信エラーでも起こるため、ここを 1 にすると警告が
     * 頻繁に出て無視されるようになる。連続して失敗している＝一過性ではない、という線引き。
     */
    private static final int DETECTION_FAILURE_ALERT_THRESHOLD = 2;

    private final MonitoredChannelRepository monitoredChannelRepository;
    private final NotificationHistoryService notificationHistoryService;
    private final RecordingHistoryService recordingHistoryService;
    private final UptimeTracker uptimeTracker;

    /**
     * 現時点のダッシュボード表示内容を組み立てて返す。
     *
     * @return 集計結果
     */
    public DashboardResponse getSnapshot() {
        List<MonitoredChannel> allChannels = monitoredChannelRepository.findAll();

        List<LiveChannelSummary> liveChannels = allChannels.stream()
                .filter(MonitoredChannel::isCurrentlyLive)
                .map(channel -> new LiveChannelSummary(
                        channel.getYoutubeChannelId(),
                        channel.getChannelName(),
                        channel.getCurrentLiveVideoId()))
                .toList();

        // 連続失敗が多い順に並べる（最も壊れている疑いが強いものを先に見せるため）
        List<DetectionFailureSummary> detectionFailingChannels = allChannels.stream()
                .filter(channel -> channel.getConsecutiveDetectionFailures() >= DETECTION_FAILURE_ALERT_THRESHOLD)
                .sorted(Comparator.comparingInt(MonitoredChannel::getConsecutiveDetectionFailures).reversed())
                .map(channel -> new DetectionFailureSummary(
                        channel.getYoutubeChannelId(),
                        channel.getChannelName(),
                        channel.getConsecutiveDetectionFailures(),
                        channel.getLastDetectionSuccessAt()))
                .toList();

        LocalDateTime since = LocalDateTime.now().minusHours(RECENT_NOTIFICATION_WINDOW_HOURS);

        return new DashboardResponse(
                allChannels.size(),
                liveChannels.size(),
                liveChannels,
                notificationHistoryService.countSince(since),
                notificationHistoryService.countFailuresSince(since),
                detectionFailingChannels,
                recordingHistoryService.countByStatus(),
                uptimeTracker.getStartedAt(),
                uptimeTracker.getUptimeSeconds()
        );
    }
}
