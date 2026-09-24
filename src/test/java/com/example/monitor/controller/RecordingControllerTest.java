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
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingController")
class RecordingControllerTest {

    @Mock
    private RecordingHistoryService recordingHistoryService;

    @InjectMocks
    private RecordingController controller;

    /** 既定の並び順（開始時刻の新しい順 → 主キーの大きい順）。 */
    private static final Sort NEWEST = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

    @Nested
    @DisplayName("getRecordings()")
    class GetRecordings {

        @Test
        @DisplayName("正常系：channelId未指定の場合は全チャンネルの録画履歴を新しい順に取得する")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Recording recording = Recording.builder()
                    .id(1L).channel(channel).videoId("video001")
                    .filePath("UCxxxxxxxx/video001.mp4")
                    .status(RecordingStatus.RECORDING).startedAt(LocalDateTime.now())
                    .build();
            when(recordingHistoryService.search(null, null, null, null, null, null, PageRequest.of(0, 20, NEWEST)))
                    .thenReturn(new PageImpl<>(List.of(recording)));

            PageResponse<RecordingResponse> result =
                    controller.getRecordings(null, null, null, "newest", null, null, null, 0, 20);

            assertThat(result.content()).hasSize(1);
        }

        @Test
        @DisplayName("正常系：channelId指定時もキーワード・状態と組み合わせて検索条件として渡す")
        void testMethod02() {
            when(recordingHistoryService.search(1L, "ASMR", RecordingStatus.COMPLETED, null, null, null,
                    PageRequest.of(0, 20, NEWEST)))
                    .thenReturn(new PageImpl<>(List.of()));

            PageResponse<RecordingResponse> result = controller.getRecordings(
                    1L, "ASMR", RecordingStatus.COMPLETED, "newest", null, null, null, 0, 20);

            assertThat(result.content()).isEmpty();
        }

        @Test
        @DisplayName("正常系：期間・ジャンルと並び順をそのまま検索条件として渡す")
        void testMethod03() {
            LocalDate from = LocalDate.of(2026, 9, 1);
            LocalDate to = LocalDate.of(2026, 9, 30);
            Sort longest = Sort.by(Sort.Order.desc("durationSeconds")).and(NEWEST);
            when(recordingHistoryService.search(null, null, null, from, to, "ASMR", PageRequest.of(0, 20, longest)))
                    .thenReturn(new PageImpl<>(List.of()));

            controller.getRecordings(null, null, null, "longest", from, to, "ASMR", 0, 20);

            verify(recordingHistoryService).search(null, null, null, from, to, "ASMR", PageRequest.of(0, 20, longest));
        }

        @Test
        @DisplayName("異常系：知らない並び順・範囲外の件数・逆転した期間はIllegalArgumentExceptionが発生する")
        void testMethod04() {
            assertThatThrownBy(() -> controller.getRecordings(null, null, null, "foo", null, null, null, 0, 20))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.getRecordings(null, null, null, "newest", null, null, null, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.getRecordings(null, null, null, "newest", null, null, null, 0, 101))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.getRecordings(null, null, null, "newest",
                    LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1), null, 0, 20))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(recordingHistoryService);
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
            when(recordingHistoryService.search(null, null, null, null, null, null, PageRequest.of(0, 2, NEWEST)))
                    .thenReturn(new PageImpl<>(List.of(recording), PageRequest.of(0, 2), 5));

            PageResponse<RecordingResponse> result =
                    controller.getRecordings(null, null, null, "newest", null, null, null, 0, 2);

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
