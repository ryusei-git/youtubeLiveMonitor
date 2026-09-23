package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.NotificationHistory;
import com.example.monitor.entity.NotificationHistory.NotificationResultType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationHistoryResponse")
class NotificationHistoryResponseTest {

    @Nested
    @DisplayName("from()")
    class From {

        @Test
        @DisplayName("正常系：成功した通知履歴がレスポンスに詰め替えられる")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setId(1L);
            LocalDateTime notifiedAt = LocalDateTime.of(2026, 9, 13, 10, 0, 0);

            NotificationHistory history = NotificationHistory.builder()
                    .id(10L)
                    .channel(channel)
                    .videoId("video001")
                    .videoTitle("配信タイトル")
                    .status(NotificationResultType.SUCCESS)
                    .errorMessage(null)
                    .notifiedAt(notifiedAt)
                    .build();

            NotificationHistoryResponse response = NotificationHistoryResponse.from(history);

            assertThat(response.id()).isEqualTo(10L);
            assertThat(response.youtubeChannelId()).isEqualTo("UCxxxxxxxx");
            assertThat(response.channelName()).isEqualTo("テストチャンネル");
            assertThat(response.videoId()).isEqualTo("video001");
            assertThat(response.videoTitle()).isEqualTo("配信タイトル");
            assertThat(response.status()).isEqualTo("SUCCESS");
            assertThat(response.errorMessage()).isNull();
            assertThat(response.notifiedAt()).isEqualTo(notifiedAt);
        }

        @Test
        @DisplayName("正常系：失敗した通知履歴はステータスと理由がFAILEDとして詰め替えられる")
        void testMethod02() {
            MonitoredChannel channel = new MonitoredChannel("UCyyyyyyyy", "別チャンネル");
            channel.setId(2L);

            NotificationHistory history = NotificationHistory.builder()
                    .id(11L)
                    .channel(channel)
                    .videoId("video002")
                    .videoTitle("配信タイトル2")
                    .status(NotificationResultType.FAILED)
                    .errorMessage("Webhook URLが未設定です")
                    .notifiedAt(LocalDateTime.now())
                    .build();

            NotificationHistoryResponse response = NotificationHistoryResponse.from(history);

            assertThat(response.status()).isEqualTo("FAILED");
            assertThat(response.errorMessage()).isEqualTo("Webhook URLが未設定です");
        }
    }
}
