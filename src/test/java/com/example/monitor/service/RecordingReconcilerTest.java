package com.example.monitor.service;

import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.repository.RecordingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingReconciler")
class RecordingReconcilerTest {

    @Mock
    private RecordingRepository recordingRepository;

    @Mock
    private RecordingFileService recordingFileService;

    @Mock
    private RecordingHistoryService recordingHistoryService;

    @Mock
    private ActiveVideoJobs activeVideoJobs;

    @Mock
    private VideoDownloadService videoDownloadService;

    @Mock
    private ProcessLauncher processLauncher;

    @Mock
    private VideoMetadataExtractor videoMetadataExtractor;

    @Mock
    private RecordingSalvager recordingSalvager;

    @InjectMocks
    private RecordingReconciler recordingReconciler;

    /**
     * 補正の対象になる録画の、ディスク上の状態を模す。
     *
     * @param recording 対象の録画履歴
     * @param outcome   {@link RecordingSalvager} が返す結果
     */
    private void stubDiskState(Recording recording, RecordingSalvager.SalvageOutcome outcome) {
        Path outputFile = Path.of("recordings").resolve(recording.getFilePath());
        when(recordingFileService.resolveFilePath(recording)).thenReturn(outputFile);
        when(recordingSalvager.ensurePlayable(outputFile)).thenReturn(outcome);
    }

    /** 最初から再生できるファイルがある状態。 */
    private RecordingSalvager.SalvageOutcome playable(long size) {
        return new RecordingSalvager.SalvageOutcome(RecordingSalvager.SalvageStatus.ALREADY_PLAYABLE, size);
    }

    /** 途中で切れていたが詰め替えて再生できるようにした状態。 */
    private RecordingSalvager.SalvageOutcome salvaged(long size) {
        return new RecordingSalvager.SalvageOutcome(RecordingSalvager.SalvageStatus.SALVAGED, size);
    }

    @Nested
    @DisplayName("reconcileOrphanedRecordings()")
    class ReconcileOrphanedRecordings {

        @Test
        @DisplayName("正常系：完成ファイルが存在する場合は完了として補正する")
        void testMethod01() {
            Recording recording = Recording.builder()
                    .id(1L).videoId("video001").status(RecordingStatus.RECORDING)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of(recording));
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of());
            when(activeVideoJobs.isActive("video001")).thenReturn(false);
            when(processLauncher.isRunningWithCommandLineContaining("video001")).thenReturn(false);
            stubDiskState(recording, playable(12345L));

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService).markCompleted(1L, 12345L);
            verify(recordingHistoryService, never()).markFailed(any());
        }

        @Test
        @DisplayName("正常系：完成ファイルが存在しない場合は失敗として補正する")
        void testMethod02() {
            Recording recording = Recording.builder()
                    .id(1L).videoId("video001").status(RecordingStatus.RECORDING)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of(recording));
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of());
            when(activeVideoJobs.isActive("video001")).thenReturn(false);
            when(processLauncher.isRunningWithCommandLineContaining("video001")).thenReturn(false);
            stubDiskState(recording, RecordingSalvager.SalvageOutcome.unavailable());

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService).markFailed(1L);
            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
        }

        @Test
        @DisplayName("正常系：置き去りの録画が無い場合は何もしない")
        void testMethod03() {
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of());
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of());

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
        }

        @Test
        @DisplayName("正常系：複数件あればそれぞれ個別に判定する")
        void testMethod04() {
            Recording completed = Recording.builder()
                    .id(1L).videoId("video001").status(RecordingStatus.RECORDING)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            Recording failed = Recording.builder()
                    .id(2L).videoId("video002").status(RecordingStatus.RECORDING)
                    .filePath("UCxxxxxxxx/video002.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING))
                    .thenReturn(List.of(completed, failed));
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of());
            when(activeVideoJobs.isActive(any())).thenReturn(false);
            when(processLauncher.isRunningWithCommandLineContaining(any())).thenReturn(false);
            stubDiskState(completed, playable(999L));
            stubDiskState(failed, RecordingSalvager.SalvageOutcome.unavailable());

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService).markCompleted(1L, 999L);
            verify(recordingHistoryService).markFailed(2L);
        }

        @Test
        @DisplayName("正常系：このプロセスが今まさに追跡中の録画は対象から除外する")
        void testMethod05() {
            // 今まさに録画が進行中で、まだ完成ファイルが無いだけの正常なケースを模している。
            // isRecording() による除外が無いと、これを誤って「失敗」と判定してしまう。
            Recording stillRecording = Recording.builder()
                    .id(1L).videoId("video001").status(RecordingStatus.RECORDING)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of(stillRecording));
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of());
            when(activeVideoJobs.isActive("video001")).thenReturn(true);

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
            verify(recordingSalvager, never()).ensurePlayable(any());
        }

        @Test
        @DisplayName("正常系：追跡は失われていても録画プロセスが稼働中なら対象から除外する")
        void testMethod06() {
            // アプリを再起動した直後を模している。yt-dlp は JVM とは独立して生き残るため、
            // このプロセス確認が無いと「まだ完成ファイルが無い」という理由だけで失敗と誤判定し、
            // 後から yt-dlp が完成させても永久に失敗表示のままになってしまう。
            Recording survivedRestart = Recording.builder()
                    .id(1L).videoId("video001").status(RecordingStatus.RECORDING)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of(survivedRestart));
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of());
            when(activeVideoJobs.isActive("video001")).thenReturn(false);
            when(processLauncher.isRunningWithCommandLineContaining("video001")).thenReturn(true);

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
            verify(recordingSalvager, never()).ensurePlayable(any());
        }

        @Test
        @DisplayName("正常系：失敗と記録されていても再生可能なファイルがあれば完了へ戻す")
        void testMethod07() {
            // 終了コードだけで失敗と判定していた頃の記録や、何らかの理由で失敗と
            // 記録されたが実際にはファイルが出来ていた場合を救済する
            Recording rescuable = Recording.builder()
                    .id(7L).videoId("video007").status(RecordingStatus.FAILED)
                    .filePath("UCxxxxxxxx/video007.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of());
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of(rescuable));
            when(recordingFileService.sizeIfExists(rescuable)).thenReturn(Optional.of(736511716L));
            stubDiskState(rescuable, playable(736511716L));

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService).markCompleted(7L, 736511716L);
        }

        @Test
        @DisplayName("正常系：失敗と記録されファイルも無いものはそのままにする")
        void testMethod08() {
            Recording trulyFailed = Recording.builder()
                    .id(8L).videoId("video008").status(RecordingStatus.FAILED)
                    .filePath("UCxxxxxxxx/video008.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of());
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of(trulyFailed));
            when(recordingFileService.sizeIfExists(trulyFailed)).thenReturn(Optional.empty());
            when(recordingFileService.hasAnyFileFor(trulyFailed)).thenReturn(false);

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
            // 何も残っていない録画で毎巡回 ffprobe を起動しないこと
            verify(recordingSalvager, never()).ensurePlayable(any());
        }

        @Test
        @DisplayName("正常系：配信途中で切れた録画は再生できる形に直して「途中まで」として記録する")
        void testMethod09() {
            // yt-dlp は最後にまとめて MP4 へ詰め替えるため、途中で止まると
            // 中身が MPEG-TS のまま残りブラウザで再生できない。詰め替えてから記録する
            Recording interrupted = Recording.builder()
                    .id(9L).videoId("video009").status(RecordingStatus.RECORDING)
                    .filePath("UCxxxxxxxx/video009.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of(interrupted));
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of());
            when(activeVideoJobs.isActive("video009")).thenReturn(false);
            when(processLauncher.isRunningWithCommandLineContaining("video009")).thenReturn(false);
            stubDiskState(interrupted, salvaged(326000000L));

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService).markPartial(9L, 326000000L);
            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
        }

        @Test
        @DisplayName("正常系：失敗と記録されていても断片が残っていれば救済を試みる")
        void testMethod10() {
            // 映像・音声が別ファイルのまま残っていたケース。以前は再生できないまま失敗扱いだった
            Recording rescuable = Recording.builder()
                    .id(10L).videoId("video010").status(RecordingStatus.FAILED)
                    .filePath("UCxxxxxxxx/video010.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of());
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of(rescuable));
            when(recordingFileService.sizeIfExists(rescuable)).thenReturn(Optional.empty());
            when(recordingFileService.hasAnyFileFor(rescuable)).thenReturn(true);
            stubDiskState(rescuable, salvaged(415000000L));

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService).markPartial(10L, 415000000L);
        }

        @Test
        @DisplayName("正常系：救済できなかった録画は状況が変わらない限り再試行しない")
        void testMethod11() {
            // 断片そのものが壊れている録画を毎巡回 ffmpeg に掛け続けると、
            // 数GBのファイルに対する外部プロセスの起動が永久に繰り返される
            Recording broken = Recording.builder()
                    .id(11L).videoId("video011").status(RecordingStatus.FAILED)
                    .filePath("UCxxxxxxxx/video011.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of());
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of(broken));
            when(recordingFileService.sizeIfExists(broken)).thenReturn(Optional.empty());
            when(recordingFileService.hasAnyFileFor(broken)).thenReturn(true);
            when(recordingFileService.totalFileSizeFor(broken)).thenReturn(4_200_000_000L);
            stubDiskState(broken, RecordingSalvager.SalvageOutcome.unavailable());

            recordingReconciler.reconcileOrphanedRecordings();
            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingSalvager, times(1)).ensurePlayable(any());
        }

        @Test
        @DisplayName("正常系：ファイルの状態が変わっていれば救済をもう一度試す")
        void testMethod12() {
            // あとから断片が揃うこともあるため、「二度と試さない」にはしない
            // （後から状況が変わった録画を救済するのがこのクラスの目的そのもの）
            Recording pending = Recording.builder()
                    .id(12L).videoId("video012").status(RecordingStatus.FAILED)
                    .filePath("UCxxxxxxxx/video012.mp4").build();
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of());
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of(pending));
            when(recordingFileService.sizeIfExists(pending)).thenReturn(Optional.empty());
            when(recordingFileService.hasAnyFileFor(pending)).thenReturn(true);
            // 1巡目の失敗時と2巡目の確認時で合計サイズが違う＝音声の断片が後から揃った状況
            when(recordingFileService.totalFileSizeFor(pending)).thenReturn(300_000_000L, 415_000_000L);
            when(recordingFileService.resolveFilePath(pending))
                    .thenReturn(Path.of("recordings").resolve(pending.getFilePath()));
            when(recordingSalvager.ensurePlayable(Path.of("recordings").resolve(pending.getFilePath())))
                    .thenReturn(RecordingSalvager.SalvageOutcome.unavailable())
                    .thenReturn(salvaged(415_000_000L));

            recordingReconciler.reconcileOrphanedRecordings();
            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingSalvager, times(2)).ensurePlayable(any());
            verify(recordingHistoryService).markPartial(12L, 415_000_000L);
        }
    }

    @Nested
    @DisplayName("reconcileOrphanedRecordings() のサムネイル生成")
    class GenerateMissingThumbnails {

        /** サムネイル生成だけを見たいので、状態の補正対象は無い状態にする。 */
        private void noRecordingsToReconcile() {
            when(recordingRepository.findByStatus(RecordingStatus.RECORDING)).thenReturn(List.of());
            when(recordingRepository.findByStatus(RecordingStatus.FAILED)).thenReturn(List.of());
        }

        @Test
        @DisplayName("正常系：サムネイル未生成の完了録画に再生時間とサムネイルを記録する")
        void testMethod01(@TempDir Path tempDir) {
            noRecordingsToReconcile();
            Recording recording = Recording.builder()
                    .id(1L).videoId("video001").status(RecordingStatus.COMPLETED)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            Path videoFile = tempDir.resolve("video001.mp4");
            Path thumbnail = tempDir.resolve("video001.jpg");
            when(recordingRepository.findByStatusInAndThumbnailPathIsNull(List.of(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL)))
                    .thenReturn(List.of(recording));
            when(recordingFileService.resolveExistingFile(recording)).thenReturn(Optional.of(videoFile));
            when(videoMetadataExtractor.extractDurationSeconds(videoFile)).thenReturn(Optional.of(5400));
            when(videoMetadataExtractor.extractThumbnail(videoFile, 5400)).thenReturn(Optional.of(thumbnail));
            when(recordingFileService.toRelativePath(thumbnail)).thenReturn("UCxxxxxxxx/video001.jpg");

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService).updateMediaMetadata(1L, 5400, "UCxxxxxxxx/video001.jpg");
        }

        @Test
        @DisplayName("正常系：サムネイルだけ作れなかった場合も再生時間は記録する")
        void testMethod02(@TempDir Path tempDir) {
            noRecordingsToReconcile();
            Recording recording = Recording.builder()
                    .id(1L).videoId("video001").status(RecordingStatus.COMPLETED)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            Path videoFile = tempDir.resolve("video001.mp4");
            when(recordingRepository.findByStatusInAndThumbnailPathIsNull(List.of(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL)))
                    .thenReturn(List.of(recording));
            when(recordingFileService.resolveExistingFile(recording)).thenReturn(Optional.of(videoFile));
            when(videoMetadataExtractor.extractDurationSeconds(videoFile)).thenReturn(Optional.of(5400));
            when(videoMetadataExtractor.extractThumbnail(videoFile, 5400)).thenReturn(Optional.empty());

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService).updateMediaMetadata(1L, 5400, null);
        }

        @Test
        @DisplayName("異常系：再生時間が読み取れない場合は次回に持ち越して何も記録しない")
        void testMethod03(@TempDir Path tempDir) {
            noRecordingsToReconcile();
            Recording recording = Recording.builder()
                    .id(1L).videoId("video001").status(RecordingStatus.COMPLETED)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            Path videoFile = tempDir.resolve("video001.mp4");
            when(recordingRepository.findByStatusInAndThumbnailPathIsNull(List.of(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL)))
                    .thenReturn(List.of(recording));
            when(recordingFileService.resolveExistingFile(recording)).thenReturn(Optional.of(videoFile));
            when(videoMetadataExtractor.extractDurationSeconds(videoFile)).thenReturn(Optional.empty());

            recordingReconciler.reconcileOrphanedRecordings();

            verify(recordingHistoryService, never()).updateMediaMetadata(any(), any(), any());
            verify(videoMetadataExtractor, never()).extractThumbnail(any(), anyInt());
        }

        @Test
        @DisplayName("異常系：録画ファイルが見つからない場合は外部コマンドを起動しない")
        void testMethod04() {
            noRecordingsToReconcile();
            Recording recording = Recording.builder()
                    .id(1L).videoId("video001").status(RecordingStatus.COMPLETED)
                    .filePath("UCxxxxxxxx/video001.mp4").build();
            when(recordingRepository.findByStatusInAndThumbnailPathIsNull(List.of(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL)))
                    .thenReturn(List.of(recording));
            when(recordingFileService.resolveExistingFile(recording)).thenReturn(Optional.empty());

            recordingReconciler.reconcileOrphanedRecordings();

            verifyNoInteractions(videoMetadataExtractor);
            verify(recordingHistoryService, never()).updateMediaMetadata(any(), any(), any());
        }
    }
}
