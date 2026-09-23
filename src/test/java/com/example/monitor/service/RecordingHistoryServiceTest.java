package com.example.monitor.service;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.exception.RecordingInProgressException;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingHistoryService")
class RecordingHistoryServiceTest {

    @Mock
    private RecordingRepository recordingRepository;

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private RecordingFileService recordingFileService;

    @InjectMocks
    private RecordingHistoryService recordingHistoryService;

    @Nested
    @DisplayName("recordStart()")
    class RecordStart {

        @Test
        @DisplayName("正常系：録画中ステータスで保存される")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            when(recordingRepository.save(any(Recording.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Recording result = recordingHistoryService.recordStart(
                    channel, "video001", "配信タイトル", "UCxxxxxxxx/video001.mp4");

            ArgumentCaptor<Recording> captor = ArgumentCaptor.forClass(Recording.class);
            verify(recordingRepository).save(captor.capture());
            Recording saved = captor.getValue();
            assertThat(saved.getChannel()).isEqualTo(channel);
            assertThat(saved.getVideoId()).isEqualTo("video001");
            assertThat(saved.getVideoTitle()).isEqualTo("配信タイトル");
            assertThat(saved.getFilePath()).isEqualTo("UCxxxxxxxx/video001.mp4");
            assertThat(saved.getStatus()).isEqualTo(RecordingStatus.RECORDING);
            assertThat(result).isSameAs(saved);
        }
    }

    @Nested
    @DisplayName("markCompleted()")
    class MarkCompleted {

        @Test
        @DisplayName("正常系：ファイルサイズと完了時刻を添えて完了状態に更新する")
        void testMethod01() {
            recordingHistoryService.markCompleted(100L, 12345L);

            verify(recordingRepository).markCompleted(eq(100L), eq(12345L), any(LocalDateTime.class));
        }
    }

    @Nested
    @DisplayName("markFailed()")
    class MarkFailed {

        @Test
        @DisplayName("正常系：失敗時刻を添えて失敗状態に更新する")
        void testMethod01() {
            recordingHistoryService.markFailed(100L);

            verify(recordingRepository).markFailed(eq(100L), any(LocalDateTime.class));
        }
    }

    @Nested
    @DisplayName("findRecent()")
    class FindRecent {

        @Test
        @DisplayName("正常系：リポジトリから取得したページをそのまま返す")
        void testMethod01() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Recording> page = new PageImpl<>(List.of());
            when(recordingRepository.findAllByOrderByStartedAtDesc(pageable)).thenReturn(page);

            Page<Recording> result = recordingHistoryService.findRecent(pageable);

            assertThat(result).isSameAs(page);
        }
    }

    @Nested
    @DisplayName("findByChannel()")
    class FindByChannel {

        @Test
        @DisplayName("正常系：存在するチャンネルIDを指定すると該当チャンネルの録画履歴を返す")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setId(1L);
            Pageable pageable = PageRequest.of(0, 20);
            Page<Recording> page = new PageImpl<>(List.of());
            when(monitoredChannelRepository.findById(1L)).thenReturn(Optional.of(channel));
            when(recordingRepository.findByChannelOrderByStartedAtDesc(channel, pageable)).thenReturn(page);

            Page<Recording> result = recordingHistoryService.findByChannel(1L, pageable);

            assertThat(result).isSameAs(page);
        }

        @Test
        @DisplayName("異常系：存在しないチャンネルIDを指定するとChannelNotFoundExceptionが発生する")
        void testMethod02() {
            Pageable pageable = PageRequest.of(0, 20);
            when(monitoredChannelRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> recordingHistoryService.findByChannel(999L, pageable))
                    .isInstanceOf(ChannelNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("deleteRecording()")
    class DeleteRecording {

        @Test
        @DisplayName("正常系：完了した録画はDBから削除しファイルも削除する")
        void testMethod01() {
            Recording recording = Recording.builder()
                    .id(100L).videoId("video001").status(RecordingStatus.COMPLETED)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            when(recordingRepository.findById(100L)).thenReturn(Optional.of(recording));

            recordingHistoryService.deleteRecording(100L);

            verify(recordingRepository).deleteById(100L);
            verify(recordingFileService).deleteFile(recording);
        }

        @Test
        @DisplayName("正常系：失敗した録画も削除できる")
        void testMethod02() {
            Recording recording = Recording.builder()
                    .id(100L).videoId("video001").status(RecordingStatus.FAILED)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            when(recordingRepository.findById(100L)).thenReturn(Optional.of(recording));

            recordingHistoryService.deleteRecording(100L);

            verify(recordingRepository).deleteById(100L);
        }

        @Test
        @DisplayName("異常系：存在しないIDを指定するとRecordingNotFoundExceptionが発生する")
        void testMethod03() {
            when(recordingRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> recordingHistoryService.deleteRecording(999L))
                    .isInstanceOf(RecordingNotFoundException.class);

            verify(recordingRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("異常系：録画中の場合はRecordingInProgressExceptionが発生し削除しない")
        void testMethod04() {
            Recording recording = Recording.builder()
                    .id(100L).videoId("video001").status(RecordingStatus.RECORDING)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            when(recordingRepository.findById(100L)).thenReturn(Optional.of(recording));

            assertThatThrownBy(() -> recordingHistoryService.deleteRecording(100L))
                    .isInstanceOf(RecordingInProgressException.class);

            verify(recordingRepository, never()).deleteById(any());
            verifyNoInteractions(recordingFileService);
        }
    }

    @Nested
    @DisplayName("search()")
    class Search {

        @Test
        @DisplayName("正常系：キーワードと状態をそのままリポジトリへ渡す")
        void testMethod01() {
            Pageable pageable = PageRequest.of(0, 20);
            when(recordingRepository.search("ASMR", RecordingStatus.COMPLETED, pageable))
                    .thenReturn(new PageImpl<>(List.of()));

            recordingHistoryService.search("ASMR", RecordingStatus.COMPLETED, pageable);

            verify(recordingRepository).search("ASMR", RecordingStatus.COMPLETED, pageable);
        }

        @Test
        @DisplayName("正常系：空白だけのキーワードは条件なし（null）として扱う")
        void testMethod02() {
            Pageable pageable = PageRequest.of(0, 20);
            when(recordingRepository.search(null, null, pageable))
                    .thenReturn(new PageImpl<>(List.of()));

            recordingHistoryService.search("   ", null, pageable);

            verify(recordingRepository).search(null, null, pageable);
        }

        @Test
        @DisplayName("正常系：キーワードの前後の空白は取り除いて渡す")
        void testMethod03() {
            Pageable pageable = PageRequest.of(0, 20);
            when(recordingRepository.search("ASMR", null, pageable))
                    .thenReturn(new PageImpl<>(List.of()));

            recordingHistoryService.search("  ASMR  ", null, pageable);

            verify(recordingRepository).search("ASMR", null, pageable);
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("正常系：該当する録画履歴を返す")
        void testMethod01() {
            Recording recording = Recording.builder()
                    .id(100L).videoId("video001").status(RecordingStatus.COMPLETED).build();
            when(recordingRepository.findById(100L)).thenReturn(Optional.of(recording));

            assertThat(recordingHistoryService.findById(100L)).isSameAs(recording);
        }

        @Test
        @DisplayName("異常系：該当する録画履歴が無い場合は例外を投げる")
        void testMethod02() {
            when(recordingRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> recordingHistoryService.findById(404L))
                    .isInstanceOf(RecordingNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("calculateDiskUsage()")
    class CalculateDiskUsage {

        @Test
        @DisplayName("正常系：RecordingFileServiceが返す集計結果をそのまま返す")
        void testMethod01() {
            DiskUsageResponse usage = new DiskUsageResponse(100L, List.of());
            when(recordingFileService.calculateUsage()).thenReturn(usage);

            DiskUsageResponse result = recordingHistoryService.calculateDiskUsage();

            assertThat(result).isSameAs(usage);
        }
    }
}
