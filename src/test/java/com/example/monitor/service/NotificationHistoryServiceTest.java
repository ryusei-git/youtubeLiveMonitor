package com.example.monitor.service;

import com.example.monitor.dto.NotificationOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.NotificationHistory;
import com.example.monitor.entity.NotificationHistory.NotificationResultType;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.NotificationHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationHistoryService")
class NotificationHistoryServiceTest {

    @Mock
    private NotificationHistoryRepository notificationHistoryRepository;

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @InjectMocks
    private NotificationHistoryService notificationHistoryService;

    @Nested
    @DisplayName("recordAttempt()")
    class RecordAttempt {

        @Test
        @DisplayName("正常系：成功結果はステータスSUCCESSとして保存される")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");

            notificationHistoryService.recordAttempt(channel, "video001", "配信タイトル", NotificationOutcome.success());

            ArgumentCaptor<NotificationHistory> captor = ArgumentCaptor.forClass(NotificationHistory.class);
            verify(notificationHistoryRepository).save(captor.capture());
            NotificationHistory saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(NotificationResultType.SUCCESS);
            assertThat(saved.getErrorMessage()).isNull();
            assertThat(saved.getVideoId()).isEqualTo("video001");
        }

        @Test
        @DisplayName("正常系：失敗結果はステータスFAILEDと理由付きで保存される")
        void testMethod02() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");

            notificationHistoryService.recordAttempt(
                    channel, "video001", "配信タイトル", NotificationOutcome.failure("送信エラー"));

            ArgumentCaptor<NotificationHistory> captor = ArgumentCaptor.forClass(NotificationHistory.class);
            verify(notificationHistoryRepository).save(captor.capture());
            NotificationHistory saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(NotificationResultType.FAILED);
            assertThat(saved.getErrorMessage()).isEqualTo("送信エラー");
        }
    }

    @Nested
    @DisplayName("findRecentHistory()")
    class FindRecentHistory {

        @Test
        @DisplayName("正常系：リポジトリから取得したページをそのまま返す")
        void testMethod01() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<NotificationHistory> page = new PageImpl<>(List.of());
            when(notificationHistoryRepository.findAllByOrderByNotifiedAtDesc(pageable)).thenReturn(page);

            Page<NotificationHistory> result = notificationHistoryService.findRecentHistory(pageable);

            assertThat(result).isSameAs(page);
        }
    }

    @Nested
    @DisplayName("findHistoryByChannel()")
    class FindHistoryByChannel {

        @Test
        @DisplayName("正常系：存在するチャンネルIDを指定すると該当チャンネルの履歴を返す")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setId(1L);
            Pageable pageable = PageRequest.of(0, 20);
            Page<NotificationHistory> page = new PageImpl<>(List.of());
            when(monitoredChannelRepository.findById(1L)).thenReturn(Optional.of(channel));
            when(notificationHistoryRepository.findByChannelOrderByNotifiedAtDesc(channel, pageable)).thenReturn(page);

            Page<NotificationHistory> result = notificationHistoryService.findHistoryByChannel(1L, pageable);

            assertThat(result).isSameAs(page);
        }

        @Test
        @DisplayName("異常系：存在しないチャンネルIDを指定するとChannelNotFoundExceptionが発生する")
        void testMethod02() {
            Pageable pageable = PageRequest.of(0, 20);
            when(monitoredChannelRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notificationHistoryService.findHistoryByChannel(999L, pageable))
                    .isInstanceOf(ChannelNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("countSince()")
    class CountSince {

        @Test
        @DisplayName("正常系：指定時刻以降の件数を返す")
        void testMethod01() {
            LocalDateTime since = LocalDateTime.of(2026, 9, 12, 0, 0, 0);
            when(notificationHistoryRepository.countByNotifiedAtAfter(since)).thenReturn(3L);

            long result = notificationHistoryService.countSince(since);

            assertThat(result).isEqualTo(3L);
        }

        @Test
        @DisplayName("正常系：該当する履歴が無い場合は0を返す")
        void testMethod02() {
            LocalDateTime since = LocalDateTime.of(2026, 9, 12, 0, 0, 0);
            when(notificationHistoryRepository.countByNotifiedAtAfter(any())).thenReturn(0L);

            long result = notificationHistoryService.countSince(since);

            assertThat(result).isZero();
        }
    }
}
