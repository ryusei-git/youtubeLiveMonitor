package com.example.monitor.controller;

import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.dto.OrphanedCleanupResponse;
import com.example.monitor.dto.PageResponse;
import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.service.RecordingHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingController")
class RecordingControllerTest {

    @Mock
    private RecordingHistoryService recordingHistoryService;

    @InjectMocks
    private RecordingController controller;

    @Nested
    @DisplayName("getRecordings()")
    class GetRecordings {

        @Test
        @DisplayName("正常系：channelId未指定の場合は全チャンネルの録画履歴を取得する")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Recording recording = Recording.builder()
                    .id(1L).channel(channel).videoId("video001")
                    .filePath("UCxxxxxxxx/video001.mp4")
                    .status(RecordingStatus.RECORDING).startedAt(LocalDateTime.now())
                    .build();
            when(recordingHistoryService.search(null, null, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(List.of(recording)));

            PageResponse<RecordingResponse> result = controller.getRecordings(null, null, null, 0, 20);

            assertThat(result.content()).hasSize(1);
            verify(recordingHistoryService, never()).findByChannel(any(), any());
        }

        @Test
        @DisplayName("正常系：channelId指定時は該当チャンネルの録画履歴のみ取得する")
        void testMethod02() {
            when(recordingHistoryService.findByChannel(eq(1L), eq(PageRequest.of(0, 20))))
                    .thenReturn(new PageImpl<>(List.of()));

            PageResponse<RecordingResponse> result = controller.getRecordings(1L, null, null, 0, 20);

            assertThat(result.content()).isEmpty();
            verify(recordingHistoryService).findByChannel(eq(1L), eq(PageRequest.of(0, 20)));
        }

        @Test
        @DisplayName("正常系：キーワードと状態を指定した場合はそのまま検索条件として渡す")
        void testMethod03() {
            when(recordingHistoryService.search("ASMR", RecordingStatus.COMPLETED, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(List.of()));

            controller.getRecordings(null, "ASMR", RecordingStatus.COMPLETED, 0, 20);

            verify(recordingHistoryService).search("ASMR", RecordingStatus.COMPLETED, PageRequest.of(0, 20));
        }

        @Test
        @DisplayName("正常系：channelIdとキーワードが両方指定された場合はchannelIdを優先する")
        void testMethod04() {
            when(recordingHistoryService.findByChannel(eq(1L), eq(PageRequest.of(0, 20))))
                    .thenReturn(new PageImpl<>(List.of()));

            controller.getRecordings(1L, "ASMR", null, 0, 20);

            verify(recordingHistoryService, never()).search(any(), any(), any());
        }

        @Test
        @DisplayName("正常系：画面が参照するページング情報（totalPages・totalElements）が引き継がれる")
        void testMethod05() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Recording recording = Recording.builder()
                    .id(1L).channel(channel).videoId("video001")
                    .filePath("UCxxxxxxxx/video001.mp4")
                    .status(RecordingStatus.COMPLETED).startedAt(LocalDateTime.now())
                    .build();
            when(recordingHistoryService.search(null, null, PageRequest.of(0, 2)))
                    .thenReturn(new PageImpl<>(List.of(recording), PageRequest.of(0, 2), 5));

            PageResponse<RecordingResponse> result = controller.getRecordings(null, null, null, 0, 2);

            assertThat(result.totalElements()).isEqualTo(5);
            assertThat(result.totalPages()).isEqualTo(3);
            assertThat(result.content()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("getRecording()")
    class GetRecording {

        @Test
        @DisplayName("正常系：指定IDの録画履歴をレスポンス型に詰め替えて返す")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setId(7L);
            Recording recording = Recording.builder()
                    .id(1L).channel(channel).videoId("video001").videoTitle("【ASMR】耳かき")
                    .filePath("UCxxxxxxxx/video001.mp4")
                    .status(RecordingStatus.COMPLETED).startedAt(LocalDateTime.now())
                    .build();
            when(recordingHistoryService.findById(1L)).thenReturn(recording);

            RecordingResponse result = controller.getRecording(1L);

            assertThat(result.id()).isEqualTo(1L);
            assertThat(result.channelId()).isEqualTo(7L);
            assertThat(result.videoTitle()).isEqualTo("【ASMR】耳かき");
        }
    }

    @Nested
    @DisplayName("deleteRecording()")
    class DeleteRecording {

        @Test
        @DisplayName("正常系：削除に成功した場合は204 No Contentを返す")
        void testMethod01() {
            ResponseEntity<Void> response = controller.deleteRecording(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(recordingHistoryService, times(1)).deleteRecording(1L);
        }
    }

    @Nested
    @DisplayName("getDiskUsage()")
    class GetDiskUsage {

        @Test
        @DisplayName("正常系：RecordingHistoryServiceが返す集計結果をそのまま返す")
        void testMethod01() {
            DiskUsageResponse usage = new DiskUsageResponse(100L, List.of());
            when(recordingHistoryService.calculateDiskUsage()).thenReturn(usage);

            DiskUsageResponse result = controller.getDiskUsage();

            assertThat(result).isSameAs(usage);
        }
    }

    @Nested
    @DisplayName("deleteOrphanedRecordings()")
    class DeleteOrphanedRecordings {

        @Test
        @DisplayName("正常系：RecordingHistoryServiceが返す削除結果をそのまま返す")
        void testMethod01() {
            OrphanedCleanupResponse cleanup =
                    new OrphanedCleanupResponse(2, 5, 1024L, List.of("UCskipped"));
            when(recordingHistoryService.deleteOrphanedRecordings()).thenReturn(cleanup);

            OrphanedCleanupResponse result = controller.deleteOrphanedRecordings();

            assertThat(result).isSameAs(cleanup);
        }
    }
}
