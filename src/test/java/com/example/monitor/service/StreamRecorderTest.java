package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StreamRecorder")
@SuppressWarnings("unchecked")
class StreamRecorderTest {

    /** 視聴URLの組み立てはプラットフォーム側の責務になったため、テストでは固定値を渡す。 */
    private static final String WATCH_URL = "https://www.youtube.com/watch?v=video001";

    @Mock
    private ProcessLauncher processLauncher;

    @Mock
    private RecordingHistoryService recordingHistoryService;

    @Mock
    private RecordingSalvager recordingSalvager;

    @BeforeEach
    void stubDefaultRecordingHistory() {
        // startRecording() が到達する行だが、テストの主眼ではないケースが多いため lenient にする
        lenient().when(recordingHistoryService.recordStart(any(), any(), any(), any()))
                .thenReturn(Recording.builder().id(1L).build());
    }

    @SuppressWarnings("unchecked")
    private Set<String> activeRecordingsOf(StreamRecorder recorder) {
        return (Set<String>) ReflectionTestUtils.getField(
                ReflectionTestUtils.getField(recorder, "activeVideoJobs"), "reservedVideoIds");
    }

    private StreamRecorder newRecorder(Path recordingDirectory) {
        return newRecorder(recordingDirectory, 1080);
    }

    private StreamRecorder newRecorder(Path recordingDirectory, int maxHeight) {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties("", ""),
                new DiscordProperties(""),
                new RecordingProperties(recordingDirectory.toString(), maxHeight),
                new MonitorProperties.AdminProperties("admin", ""));
        return new StreamRecorder(
                properties, processLauncher, recordingHistoryService, recordingSalvager, new ActiveVideoJobs());
    }

    @Nested
    @DisplayName("startRecording()")
    class StartRecording {

        @Test
        @DisplayName("正常系：yt-dlpを正しい引数で起動する")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            // 録画完了待ちは別の仮想スレッドで非同期に行われるため、このスタブが
            // アサーション実施までに消費される保証がない。lenient にして
            // UnnecessaryStubbingException による誤検知を避ける。
            lenient().when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(processLauncher.launch(any())).thenReturn(mockProcess);

            recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processLauncher).launch(captor.capture());
            List<String> command = captor.getValue();

            assertThat(command).contains("yt-dlp", "--live-from-start", "--merge-output-format", "mp4");
            assertThat(command).anyMatch(arg -> arg.contains("height<=1080"));
            assertThat(command).anyMatch(arg -> arg.contains("video001"));
            assertThat(command.get(command.size() - 1))
                    .isEqualTo("https://www.youtube.com/watch?v=video001");
        }

        @Test
        @DisplayName("正常系：起動に成功した場合はtrueを返す")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            lenient().when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(processLauncher.launch(any())).thenReturn(mockProcess);

            boolean result = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系：既に同じ動画IDを録画中の場合は再度起動せずtrueを返す")
        void testMethod03(@TempDir Path tempDir) {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            activeRecordingsOf(recorder).add("video001");

            boolean result = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            assertThat(result).isTrue();
            verifyNoInteractions(processLauncher);
            verifyNoInteractions(recordingHistoryService);
        }

        @Test
        @DisplayName("異常系：プロセス起動でIOExceptionが発生した場合はfalseを返す")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            when(processLauncher.launch(any())).thenThrow(new IOException("yt-dlp: command not found"));

            boolean result = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            assertThat(result).isFalse();
            verifyNoInteractions(recordingHistoryService);
        }

        @Test
        @DisplayName("異常系：保存先ディレクトリの作成に失敗した場合はfalseを返す")
        void testMethod05(@TempDir Path tempDir) throws IOException {
            // チャンネルIDと同名の「ファイル」を先に作っておくと、同名でのディレクトリ作成が失敗する
            Files.writeString(tempDir.resolve("UCxxxxxxxx"), "not a directory");
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");

            boolean result = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            assertThat(result).isFalse();
            verifyNoInteractions(processLauncher);
            verifyNoInteractions(recordingHistoryService);
        }

        @Test
        @DisplayName("正常系：画質上限が未設定(0以下)の場合は解像度フィルタを付けずに最高画質で起動する")
        void testMethod06(@TempDir Path tempDir) throws IOException {
            StreamRecorder recorder = newRecorder(tempDir, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            lenient().when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(processLauncher.launch(any())).thenReturn(mockProcess);

            recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processLauncher).launch(captor.capture());
            List<String> command = captor.getValue();

            assertThat(command).contains("bestvideo+bestaudio/best");
            assertThat(command).noneMatch(arg -> arg.contains("height<="));
        }

        @Test
        @DisplayName("正常系：起動に成功すると録画履歴に開始時点の情報を記録する")
        void testMethod07(@TempDir Path tempDir) throws IOException {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            lenient().when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(processLauncher.launch(any())).thenReturn(mockProcess);

            recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            verify(recordingHistoryService)
                    .recordStart(channel, "video001", "配信タイトル", "UCxxxxxxxx/video001.mp4");
        }

        @Test
        @DisplayName("異常系：起動に失敗した動画IDは同じ動画IDで録画をやり直せる")
        void testMethod08(@TempDir Path tempDir) throws IOException {
            // 二重起動を防ぐための登録が失敗時に残ると、それを外す者がいないため
            // この動画IDは以降永久に録画できなくなる
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            lenient().when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(processLauncher.launch(any()))
                    .thenThrow(new IOException("yt-dlp: command not found"))
                    .thenReturn(mockProcess);

            boolean firstAttempt = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");
            boolean retry = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            assertThat(firstAttempt).isFalse();
            assertThat(retry).isTrue();
            // 2回目が「既に録画中」と誤判定されて素通りしていないこと
            verify(processLauncher, times(2)).launch(any());
        }

        @Test
        @DisplayName("異常系：保存先ディレクトリを作れなかった動画IDも録画をやり直せる")
        void testMethod09(@TempDir Path tempDir) throws IOException {
            // 起動より手前で失敗した場合も、登録を残したままにしてはならない
            Path blockingFile = tempDir.resolve("UCxxxxxxxx");
            Files.writeString(blockingFile, "not a directory");
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");

            assertThat(recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル")).isFalse();

            assertThat(recorder.isRecording("video001")).isFalse();
        }
    }

    @Nested
    @DisplayName("isRecording()")
    class IsRecording {

        @Test
        @DisplayName("正常系：録画中の動画IDに対してtrueを返す")
        void testMethod01(@TempDir Path tempDir) {
            StreamRecorder recorder = newRecorder(tempDir);
            activeRecordingsOf(recorder).add("video001");

            assertThat(recorder.isRecording("video001")).isTrue();
        }

        @Test
        @DisplayName("正常系：録画していない動画IDに対してfalseを返す")
        void testMethod02(@TempDir Path tempDir) {
            StreamRecorder recorder = newRecorder(tempDir);

            assertThat(recorder.isRecording("video999")).isFalse();
        }
    }

    @Nested
    @DisplayName("awaitCompletion()")
    class AwaitCompletion {

        /** 最初から再生できるファイルが出来ている状態を模す。 */
        private void stubFileExists(long size) {
            when(recordingSalvager.ensurePlayable(any(Path.class)))
                    .thenReturn(new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, size));
        }

        /** 配信途中で切れたが、詰め替えて再生できるようにした状態を模す。 */
        private void stubFileSalvaged(long size) {
            when(recordingSalvager.ensurePlayable(any(Path.class)))
                    .thenReturn(new SalvageOutcome(SalvageStatus.SALVAGED, size));
        }

        /** 再生できるファイルを用意できなかった状態を模す。 */
        private void stubFileMissing() {
            when(recordingSalvager.ensurePlayable(any(Path.class)))
                    .thenReturn(SalvageOutcome.unavailable());
        }

        private Process processExiting(int exitCode) throws Exception {
            Process mockProcess = mock(Process.class);
            when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(mockProcess.waitFor()).thenReturn(exitCode);
            return mockProcess;
        }

        @Test
        @DisplayName("正常系：正常終了(exitCode=0)し完成ファイルが存在する場合は録画完了として記録される")
        void testMethod01(@TempDir Path tempDir) throws Exception {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            when(mockProcess.getInputStream())
                    .thenReturn(new ByteArrayInputStream("progress line\n".getBytes(StandardCharsets.UTF_8)));
            when(mockProcess.waitFor()).thenReturn(0);
            activeRecordingsOf(recorder).add("video001");
            stubFileExists(12345L);

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null);

            assertThat(recorder.isRecording("video001")).isFalse();
            verify(recordingHistoryService).markCompleted(100L, 12345L);
        }

        @Test
        @DisplayName("正常系：異常終了(exitCode!=0)でも完成ファイルがあれば録画完了として記録される")
        void testMethod02(@TempDir Path tempDir) throws Exception {
            // 実際に発生したケース。配信終了間際に最後の数フラグメントを取得できず
            // yt-dlp は終了コード 1 を返すが、残りは最後までダウンロードしてマージも完了しており
            // 再生可能なファイルが出来ている。これを失敗扱いにすると数時間ぶんの録画が無駄になる。
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = processExiting(1);
            activeRecordingsOf(recorder).add("video001");
            stubFileExists(736511716L);

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null);

            assertThat(recorder.isRecording("video001")).isFalse();
            verify(recordingHistoryService).markCompleted(100L, 736511716L);
            verify(recordingHistoryService, never()).markFailed(any());
        }

        @Test
        @DisplayName("異常系：異常終了(exitCode!=0)で完成ファイルも無い場合は録画失敗として記録される")
        void testMethod03(@TempDir Path tempDir) throws Exception {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = processExiting(1);
            activeRecordingsOf(recorder).add("video001");
            stubFileMissing();

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null);

            assertThat(recorder.isRecording("video001")).isFalse();
            verify(recordingHistoryService).markFailed(100L);
        }

        @Test
        @DisplayName("異常系：正常終了(exitCode=0)でも完成ファイルが見つからない場合は録画失敗として記録される")
        void testMethod04(@TempDir Path tempDir) throws Exception {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = processExiting(0);
            activeRecordingsOf(recorder).add("video001");
            stubFileMissing();

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null);

            verify(recordingHistoryService).markFailed(100L);
            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
        }

        @Test
        @DisplayName("異常系：完了待ちが割り込まれ完成ファイルも無い場合は録画失敗として記録される")
        void testMethod05(@TempDir Path tempDir) throws Exception {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(mockProcess.waitFor()).thenThrow(new InterruptedException());
            activeRecordingsOf(recorder).add("video001");
            stubFileMissing();

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null);

            assertThat(recorder.isRecording("video001")).isFalse();
            assertThat(Thread.interrupted()).isTrue(); // 割り込みフラグを消費して後片付けする
            verify(recordingHistoryService).markFailed(100L);
        }

        @Test
        @DisplayName("正常系：完了待ちが割り込まれても完成ファイルがあれば録画完了として記録される")
        void testMethod06(@TempDir Path tempDir) throws Exception {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(mockProcess.waitFor()).thenThrow(new InterruptedException());
            activeRecordingsOf(recorder).add("video001");
            stubFileExists(555L);

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null);

            assertThat(Thread.interrupted()).isTrue();
            verify(recordingHistoryService).markCompleted(100L, 555L);
            verify(recordingHistoryService, never()).markFailed(any());
        }

        @Test
        @DisplayName("正常系：配信途中で切れた録画は「途中まで」として記録される")
        void testMethod07(@TempDir Path tempDir) throws Exception {
            // yt-dlp は最後にまとめて MP4 へ詰め替えるため、途中で止まると再生できない形で残る。
            // 詰め替えれば再生できるので失敗にはせず、完了とも区別して記録する
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = processExiting(1);
            activeRecordingsOf(recorder).add("video001");
            stubFileSalvaged(326000000L);

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null);

            verify(recordingHistoryService).markPartial(100L, 326000000L);
            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
        }

        @Test
        @DisplayName("正常系：録画の終了後に詰め替えを行う（書き込み中のファイルを壊さないため）")
        void testMethod08(@TempDir Path tempDir) throws Exception {
            // 詰め替えは出力ファイルを置き換えるため、プロセスが生きているうちに走らせてはならない。
            // awaitCompletion は waitFor() の後に呼ぶ作りになっていることを固定する
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = processExiting(0);
            activeRecordingsOf(recorder).add("video001");
            stubFileExists(100L);

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null);

            InOrder inOrder = inOrder(mockProcess, recordingSalvager);
            inOrder.verify(mockProcess).waitFor();
            inOrder.verify(recordingSalvager).ensurePlayable(any(Path.class));
        }
    }
}
