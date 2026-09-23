package com.example.monitor.controller;

import com.example.monitor.dto.NotificationHistoryResponse;
import com.example.monitor.dto.PageResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.NotificationHistory;
import com.example.monitor.entity.NotificationHistory.NotificationResultType;
import com.example.monitor.service.NotificationHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationHistoryController")
class NotificationHistoryControllerTest {

    @Mock
    private NotificationHistoryService notificationHistoryService;

    @InjectMocks
    private NotificationHistoryController controller;

    @Nested
    @DisplayName("getHistory()")
    class GetHistory {

        @Test
        @DisplayName("正常系：channelId未指定の場合は全チャンネルの履歴を取得する")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            NotificationHistory history = NotificationHistory.builder()
                    .id(1L).channel(channel).videoId("video001")
                    .status(NotificationResultType.SUCCESS).notifiedAt(java.time.LocalDateTime.now())
                    .build();
            when(notificationHistoryService.findRecentHistory(PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(java.util.List.of(history)));

            PageResponse<NotificationHistoryResponse> result = controller.getHistory(null, 0, 20);

            assertThat(result.content()).hasSize(1);
            verify(notificationHistoryService, never()).findHistoryByChannel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("正常系：channelId指定時は該当チャンネルの履歴のみ取得する")
        void testMethod02() {
            when(notificationHistoryService.findHistoryByChannel(eq(1L), eq(PageRequest.of(0, 20))))
                    .thenReturn(new PageImpl<>(java.util.List.of()));

            PageResponse<NotificationHistoryResponse> result = controller.getHistory(1L, 0, 20);

            assertThat(result.content()).isEmpty();
            verify(notificationHistoryService).findHistoryByChannel(eq(1L), eq(PageRequest.of(0, 20)));
        }

        @Test
        @DisplayName("正常系：画面が参照するページング情報（totalPages）が引き継がれる")
        void testMethod03() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            NotificationHistory history = NotificationHistory.builder()
                    .id(1L).channel(channel).videoId("video001")
                    .status(NotificationResultType.SUCCESS).notifiedAt(java.time.LocalDateTime.now())
                    .build();
            when(notificationHistoryService.findRecentHistory(PageRequest.of(0, 2)))
                    .thenReturn(new PageImpl<>(java.util.List.of(history), PageRequest.of(0, 2), 15));

            PageResponse<NotificationHistoryResponse> result = controller.getHistory(null, 0, 2);

            assertThat(result.totalElements()).isEqualTo(15);
            assertThat(result.totalPages()).isEqualTo(8);
        }
    }
}
