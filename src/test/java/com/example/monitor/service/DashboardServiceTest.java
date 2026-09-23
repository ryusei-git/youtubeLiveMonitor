package com.example.monitor.service;

import com.example.monitor.dto.DashboardResponse.RecordingStatusSummary;
import com.example.monitor.dto.DashboardResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.repository.MonitoredChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService")
class DashboardServiceTest {

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private NotificationHistoryService notificationHistoryService;

    @Mock
    private RecordingHistoryService recordingHistoryService;

    @Mock
    private UptimeTracker uptimeTracker;

    @InjectMocks
    private DashboardService dashboardService;

    @BeforeEach
    void stubRecordingStatusSummary() {
        // getSnapshot() が必ず通る行だが、録画の内訳まで検証するテストは一部のため lenient にする
        lenient().when(recordingHistoryService.countByStatus())
                .thenReturn(new RecordingStatusSummary(0, 0, 0, 0));
    }

    @Nested
    @DisplayName("getSnapshot()")
    class GetSnapshot {

        @Test
        @DisplayName("正常系：配信中のチャンネルのみliveNowChannelsに含まれる")
        void testMethod01() {
            MonitoredChannel liveChannel = new MonitoredChannel("UClive0000", "配信中チャンネル");
            liveChannel.setCurrentlyLive(true);
            liveChannel.setCurrentLiveVideoId("video001");
            MonitoredChannel offlineChannel = new MonitoredChannel("UCoffline0", "非配信チャンネル");
            offlineChannel.setCurrentlyLive(false);

            when(monitoredChannelRepository.findAll()).thenReturn(List.of(liveChannel, offlineChannel));
            when(notificationHistoryService.countSince(any())).thenReturn(5L);
            when(uptimeTracker.getStartedAt()).thenReturn(LocalDateTime.of(2026, 9, 13, 0, 0, 0));
            when(uptimeTracker.getUptimeSeconds()).thenReturn(3600L);

            DashboardResponse response = dashboardService.getSnapshot();

            assertThat(response.totalChannels()).isEqualTo(2);
            assertThat(response.liveNowCount()).isEqualTo(1);
            assertThat(response.liveNowChannels()).hasSize(1);
            assertThat(response.liveNowChannels().get(0).youtubeChannelId()).isEqualTo("UClive0000");
            assertThat(response.notificationsLast24h()).isEqualTo(5L);
            assertThat(response.uptimeSeconds()).isEqualTo(3600L);
        }

        @Test
        @DisplayName("正常系：チャンネルが1件も登録されていない場合は全項目が0または空になる")
        void testMethod02() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());
            when(notificationHistoryService.countSince(any())).thenReturn(0L);
            when(uptimeTracker.getStartedAt()).thenReturn(LocalDateTime.of(2026, 9, 13, 0, 0, 0));
            when(uptimeTracker.getUptimeSeconds()).thenReturn(0L);

            DashboardResponse response = dashboardService.getSnapshot();

            assertThat(response.totalChannels()).isZero();
            assertThat(response.liveNowCount()).isZero();
            assertThat(response.liveNowChannels()).isEmpty();
        }

        @Test
        @DisplayName("正常系：連続失敗が閾値以上のチャンネルだけが警告一覧に含まれる")
        void testMethod03() {
            MonitoredChannel healthy = new MonitoredChannel("UChealthy0", "正常チャンネル");
            MonitoredChannel onceFailledOnly = new MonitoredChannel("UConce0000", "1回だけ失敗");
            onceFailledOnly.setConsecutiveDetectionFailures(1);
            MonitoredChannel failing = new MonitoredChannel("UCfailing0", "継続失敗チャンネル");
            failing.setConsecutiveDetectionFailures(5);

            when(monitoredChannelRepository.findAll())
                    .thenReturn(List.of(healthy, onceFailledOnly, failing));
            when(notificationHistoryService.countSince(any())).thenReturn(0L);
            when(uptimeTracker.getStartedAt()).thenReturn(LocalDateTime.of(2026, 9, 13, 0, 0, 0));
            when(uptimeTracker.getUptimeSeconds()).thenReturn(0L);

            DashboardResponse response = dashboardService.getSnapshot();

            // 1回だけの失敗は一過性の通信エラーでも起きるため警告にしない
            assertThat(response.detectionFailingChannels()).hasSize(1);
            assertThat(response.detectionFailingChannels().get(0).youtubeChannelId()).isEqualTo("UCfailing0");
            assertThat(response.detectionFailingChannels().get(0).consecutiveFailures()).isEqualTo(5);
        }

        @Test
        @DisplayName("正常系：警告一覧は連続失敗回数の多い順に並ぶ")
        void testMethod04() {
            MonitoredChannel lessFailing = new MonitoredChannel("UCless0000", "失敗少");
            lessFailing.setConsecutiveDetectionFailures(2);
            MonitoredChannel moreFailing = new MonitoredChannel("UCmore0000", "失敗多");
            moreFailing.setConsecutiveDetectionFailures(9);

            when(monitoredChannelRepository.findAll()).thenReturn(List.of(lessFailing, moreFailing));
            when(notificationHistoryService.countSince(any())).thenReturn(0L);
            when(uptimeTracker.getStartedAt()).thenReturn(LocalDateTime.of(2026, 9, 13, 0, 0, 0));
            when(uptimeTracker.getUptimeSeconds()).thenReturn(0L);

            DashboardResponse response = dashboardService.getSnapshot();

            assertThat(response.detectionFailingChannels())
                    .extracting(DashboardResponse.DetectionFailureSummary::youtubeChannelId)
                    .containsExactly("UCmore0000", "UCless0000");
        }

        @Test
        @DisplayName("正常系：直近24時間の通知失敗件数を集計して返す")
        void testMethod05() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());
            when(notificationHistoryService.countSince(any())).thenReturn(10L);
            when(notificationHistoryService.countFailuresSince(any())).thenReturn(3L);
            when(uptimeTracker.getStartedAt()).thenReturn(LocalDateTime.of(2026, 9, 13, 0, 0, 0));
            when(uptimeTracker.getUptimeSeconds()).thenReturn(0L);

            DashboardResponse response = dashboardService.getSnapshot();

            assertThat(response.notificationsLast24h()).isEqualTo(10L);
            assertThat(response.notificationFailuresLast24h()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("getSnapshot() の録画内訳")
    class RecordingStatus {

        @Test
        @DisplayName("正常系：録画履歴の状態別件数をそのまま返す")
        void testMethod01() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());
            when(recordingHistoryService.countByStatus())
                    .thenReturn(new RecordingStatusSummary(12, 2, 3, 1));

            DashboardResponse result = dashboardService.getSnapshot();

            assertThat(result.recordingStatus().completed()).isEqualTo(12);
            assertThat(result.recordingStatus().recording()).isEqualTo(3);
            assertThat(result.recordingStatus().failed()).isEqualTo(1);
        }
    }
}
