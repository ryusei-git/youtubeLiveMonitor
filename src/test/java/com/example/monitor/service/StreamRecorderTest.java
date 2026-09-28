package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.notification.DiscordNotifier;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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

    /** 録り直し用のコマンド。中身は見ず、このインスタンスで起動したかだけを確かめる。 */
    private static final List<String> FALLBACK_COMMAND = List.of("yt-dlp", "--no-part", WATCH_URL);

    @Mock
    private ProcessLauncher processLauncher;

    @Mock
    private RecordingHistoryService recordingHistoryService;

    @Mock
    private RecordingSalvager recordingSalvager;

    @Mock
    private DiscordNotifier discordNotifier;

    @BeforeEach
    void stubDefaultRecordingHistory() {
        // startRecording() が到達する行だが、テストの主眼ではないケースが多いため lenient にする
        lenient().when(recordingHistoryService.recordStart(any(), any(), any(), any()))
                .thenReturn(Recording.builder().id(1L).build());
        // 行が無いと awaitCompletion() は記録を飛ばす（チャンネル削除時の扱い）。通常は行がある
        lenient().when(recordingHistoryService.exists(any())).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private Set<String> activeRecordingsOf(StreamRecorder recorder) {
        return (Set<String>) ReflectionTestUtils.getField(
                ReflectionTestUtils.getField(recorder, "activeVideoJobs"), "reservedVideoIds");
    }

    @SuppressWarnings("unchecked")
    private Set<String> stopRequestedOf(StreamRecorder recorder) {
        return (Set<String>) ReflectionTestUtils.getField(recorder, "stopRequested");
    }

    private StreamRecorder newRecorder(Path recordingDirectory) {
        return newRecorder(recordingDirectory, 1080);
    }

    /**
     * 予約機構を共有した状態の録画係を作る。
     *
     * <p>自動録画と手動ダウンロードは<b>同じ {@link ActiveVideoJobs} を共有している</b>。
     * 別々の集合を持たせると同時開始を防げないため、その共有が効いていることを
     * 確かめるにはこちらを使う。
     *
     * @param recordingDirectory 保存先
     * @param activeVideoJobs    共有する予約機構
     * @return 録画係
     */
    private StreamRecorder newRecorder(Path recordingDirectory, ActiveVideoJobs activeVideoJobs) {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties("", ""),
                new DiscordProperties(""),
                new RecordingProperties(recordingDirectory.toString(), 1080),
                new MonitorProperties.AdminProperties("admin", ""));
        return new StreamRecorder(
                properties, processLauncher, recordingHistoryService, recordingSalvager, activeVideoJobs,
                discordNotifier);
    }

    private StreamRecorder newRecorder(Path recordingDirectory, int maxHeight) {
        return newRecorder(recordingDirectory, maxHeight, 120);
    }

    /**
     * 巡回の間隔を指定して録画係を作る。
     *
     * <ul>
     *   <li>巡回の合図を待つ長さは {@code intervalSeconds} の 3 倍（{@code LIVE_CONFIRMATION_WAIT_CYCLES}）になる。</li>
     *   <li>0 にすると、合図を待たずに「来なかった」になる。</li>
     * </ul>
     *
     * @param recordingDirectory 保存先
     * @param maxHeight          画質の上限
     * @param intervalSeconds    巡回の間隔（秒）
     * @return 録画係
     */
    private StreamRecorder newRecorder(Path recordingDirectory, int maxHeight, int intervalSeconds) {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", intervalSeconds),
                new TwitchProperties("", ""),
                new DiscordProperties(""),
                new RecordingProperties(recordingDirectory.toString(), maxHeight),
                new MonitorProperties.AdminProperties("admin", ""));
        return new StreamRecorder(
                properties, processLauncher, recordingHistoryService, recordingSalvager, new ActiveVideoJobs(),
                discordNotifier);
    }

    @Nested
    @DisplayName("startRecording()")
    class StartRecording {

        /**
         * 起動に成功したテストでは、裏の仮想スレッドが {@code awaitCompletion} まで進む。
         * {@code ensurePlayable} をスタブしないと {@code null} が返り、{@code salvage.isPlayable()} の NPE でスレッドが落ちる。
         * 以前は、この NPE で録り直しの {@code launch} が走らないことに頼って、{@code launch} の回数の検証が安定していた。
         * 「最初から再生できた」を返し、録り直しに進まずに正常に終わらせる。消費されるかはタイミングしだいなので lenient にする。
         */
        @BeforeEach
        void completeBackgroundRecordingWithoutRetry() {
            lenient().when(recordingSalvager.ensurePlayable(any()))
                    .thenReturn(new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, 1L));
        }

        /**
         * 起動直後に終わる録画プロセスを模す。完了待ちの仮想スレッドは {@code waitFor(1, 分)} を繰り返すため、
         * スタブしないと既定の {@code false} で空回りし続ける。テストより先に終わることもあるので lenient にする。
         * 完了待ちが録り直しに進まないよう、{@code ensurePlayable} は {@code @BeforeEach} で「最初から再生できた」にしてある。
         */
        private Process exitedProcess() {
            Process process = mock(Process.class);
            try {
                lenient().when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return process;
        }

        @Test
        @DisplayName("正常系：yt-dlpを正しい引数で起動する")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(mockProcess);

            recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processLauncher).launch(captor.capture(), any());
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
            Process mockProcess = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(mockProcess);

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
            when(processLauncher.launch(any(), any())).thenThrow(new IOException("yt-dlp: command not found"));

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
            Process mockProcess = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(mockProcess);

            recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processLauncher).launch(captor.capture(), any());
            List<String> command = captor.getValue();

            assertThat(command).contains("bestvideo+bestaudio/best");
            assertThat(command).noneMatch(arg -> arg.contains("height<="));
        }

        @Test
        @DisplayName("正常系：起動に成功すると録画履歴に開始時点の情報を記録する")
        void testMethod07(@TempDir Path tempDir) throws IOException {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(mockProcess);

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
            Process mockProcess = exitedProcess();
            when(processLauncher.launch(any(), any()))
                    .thenThrow(new IOException("yt-dlp: command not found"))
                    .thenReturn(mockProcess);

            boolean firstAttempt = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");
            boolean retry = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            assertThat(firstAttempt).isFalse();
            assertThat(retry).isTrue();
            // 2回目が「既に録画中」と誤判定されて素通りしていないこと
            verify(processLauncher, times(2)).launch(any(), any());
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

        @Test
        @DisplayName("異常系：手動ダウンロード中の動画は録画を開始しない")
        void testMethod10(@TempDir Path tempDir) throws IOException {
            // 同じ動画IDへ2つの yt-dlp が同じ出力先に書き込むと録画そのものが壊れる。
            // ダウンロード側が先に予約を取っている状態を作る
            ActiveVideoJobs shared = new ActiveVideoJobs();
            shared.reserve("video001");
            StreamRecorder recorder = newRecorder(tempDir, shared);
            MonitoredChannel channel = new MonitoredChannel("UCSMOQeBJ2RAnuFungnQOxLg", "テストチャンネル");

            // 既に取得中なので成功として扱う（呼び出し側から見れば目的は達成されている）
            assertThat(recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル")).isTrue();

            verify(processLauncher, never()).launch(any(), any());
        }

        @Test
        @DisplayName("異常系：履歴の登録に失敗したら起動済みプロセスを停止して予約を解放する")
        void testMethod11(@TempDir Path tempDir) throws IOException, InterruptedException {
            // 起動済みのプロセスを放置すると、次の巡回で同じ出力先に別プロセスが起動する
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCSMOQeBJ2RAnuFungnQOxLg", "テストチャンネル");
            Process process = mock(Process.class);
            when(processLauncher.launch(any(), any())).thenReturn(process);
            when(recordingHistoryService.recordStart(any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("DB障害"));

            assertThatThrownBy(() -> recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル"))
                    .isInstanceOf(IllegalStateException.class);

            verify(process).destroyForcibly();
            // destroyForcibly() だけでは終わらない。実際に終了したことを確認してから解放する
            verify(process).waitFor();
            assertThat(recorder.isRecording("video001")).isFalse();
        }

        @Test
        @DisplayName("正常系：解放後は同じ動画IDで再び録画を開始できる")
        void testMethod12(@TempDir Path tempDir) throws IOException {
            // 解放し損ねると、その配信は以降永久に録画できなくなる
            ActiveVideoJobs shared = new ActiveVideoJobs();
            shared.reserve("video001");
            shared.release("video001");
            StreamRecorder recorder = newRecorder(tempDir, shared);
            MonitoredChannel channel = new MonitoredChannel("UCSMOQeBJ2RAnuFungnQOxLg", "テストチャンネル");
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            assertThat(recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル")).isTrue();

            verify(processLauncher).launch(any(), any());
        }

        @Test
        @DisplayName("異常系：空き容量がしきい値を下回ると起動せずfalseを返し、続けて弾かれても管理者への通知は1回だけ")
        void testMethod13(@TempDir Path tempDir) throws IOException {
            // 巡回（2 分ごと）のたびに弾かれるので、毎回知らせると通知が溢れる
            StreamRecorder recorder = newRecorder(tempDir);
            // 100 万 GB。どの機械の空きも下回る
            ReflectionTestUtils.setField(recorder, "minFreeGb", 1_000_000L);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");

            boolean first = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");
            boolean second = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            assertThat(first).isFalse();
            assertThat(second).isFalse();
            verify(processLauncher, never()).launch(any(), any());
            // FAILED を記録すると巡回のたびに行が増える
            verify(recordingHistoryService, never()).recordStart(any(), any(), any(), any());
            verify(discordNotifier, times(1)).sendAdminAlert(contains("空き容量"));
            assertThat(recorder.isRecording("video001")).isFalse();
        }

        @Test
        @DisplayName("正常系：空き容量が戻って録画を始めた後に再び下回ると、改めて管理者へ通知する")
        void testMethod14(@TempDir Path tempDir) throws IOException {
            // 動画 ID を呼び出しごとに変える。起動に成功した video002 の予約は裏のスレッドが終わるまで残るため
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            ReflectionTestUtils.setField(recorder, "minFreeGb", 1_000_000L);
            boolean lowDisk = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");
            ReflectionTestUtils.setField(recorder, "minFreeGb", 0L);
            boolean recovered = recorder.startRecording(channel, WATCH_URL, "video002", "配信タイトル");
            ReflectionTestUtils.setField(recorder, "minFreeGb", 1_000_000L);
            boolean lowDiskAgain = recorder.startRecording(channel, WATCH_URL, "video003", "配信タイトル");

            assertThat(lowDisk).isFalse();
            assertThat(recovered).isTrue();
            assertThat(lowDiskAgain).isFalse();
            verify(discordNotifier, times(2)).sendAdminAlert(contains("空き容量"));
        }

        @Test
        @DisplayName("異常系：yt-dlpを起動できない失敗が続いても通知は1回で、起動できた後にまた失敗すると改めて通知する")
        void testMethod15(@TempDir Path tempDir) throws IOException {
            // 失敗を返すと巡回のたびに再び試みるので、直るまで毎回知らせると通知が溢れる
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any()))
                    .thenThrow(new IOException("yt-dlp: command not found"))
                    .thenThrow(new IOException("yt-dlp: command not found"))
                    .thenReturn(process)
                    .thenThrow(new IOException("yt-dlp: command not found"));

            boolean first = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");
            boolean second = recorder.startRecording(channel, WATCH_URL, "video002", "配信タイトル");
            boolean third = recorder.startRecording(channel, WATCH_URL, "video003", "配信タイトル");
            boolean fourth = recorder.startRecording(channel, WATCH_URL, "video004", "配信タイトル");

            assertThat(List.of(first, second, third, fourth)).containsExactly(false, false, true, false);
            verify(discordNotifier, times(2)).sendAdminAlert(contains("起動できない"));
        }

        @Test
        @DisplayName("正常系：前に止めた印が残っていても、同じ動画IDで録画を始めるときに外す")
        void testMethod16(@TempDir Path tempDir) throws IOException {
            // 手動ダウンロードを止めたときは印を外す者がいない。残ったままだと、これから始める録画が録り直さなくなる
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            stopRequestedOf(recorder).add("video001");
            // 起動に失敗させて裏のスレッドを作らない。裏のスレッドも終わりに印を外すので、
            // 起動に成功させると、外したのがどちらか区別できない
            when(processLauncher.launch(any(), any())).thenThrow(new IOException("yt-dlp: command not found"));

            boolean result = recorder.startRecording(channel, WATCH_URL, "video001", "配信タイトル");

            assertThat(result).isFalse();
            assertThat(stopRequestedOf(recorder)).doesNotContain("video001");
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
    @DisplayName("stopRecording()")
    class StopRecording {

        private Recording recordingWithStatus(Recording.RecordingStatus status) {
            return Recording.builder().id(100L).videoId("video001").filePath("UCxxxxxxxx/video001.mp4")
                    .status(status).build();
        }

        /** 終了コード 1 ですぐに終わる録画プロセスを模す（{@code AwaitCompletion} の {@code processExiting(1)} と同じ）。 */
        private Process exitingProcess() throws InterruptedException {
            Process process = mock(Process.class);
            when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(process.exitValue()).thenReturn(1);
            return process;
        }

        @Test
        @DisplayName("異常系：録画中でない録画は止めない")
        void testMethod01(@TempDir Path tempDir) {
            StreamRecorder recorder = newRecorder(tempDir);
            Recording recording = recordingWithStatus(Recording.RecordingStatus.COMPLETED);
            when(recordingHistoryService.findById(100L)).thenReturn(recording);

            StreamRecorder.StopOutcome result = recorder.stopRecording(100L);

            assertThat(result).isEqualTo(StreamRecorder.StopOutcome.NOT_RECORDING);
            verify(processLauncher, never()).findYtDlpProcessesWithCommandLineContaining(any());
            assertThat(stopRequestedOf(recorder)).isEmpty();
        }

        @Test
        @DisplayName("異常系：止めるプロセスが無く、このアプリも追跡していなければ何もしない")
        void testMethod02(@TempDir Path tempDir) {
            // 既に終わり、後始末（RecordingReconciler）を待っている録画
            StreamRecorder recorder = newRecorder(tempDir);
            Recording recording = recordingWithStatus(Recording.RecordingStatus.RECORDING);
            when(recordingHistoryService.findById(100L)).thenReturn(recording);
            when(processLauncher.findYtDlpProcessesWithCommandLineContaining("UCxxxxxxxx/video001.%(ext)s"))
                    .thenReturn(List.of());

            StreamRecorder.StopOutcome result = recorder.stopRecording(100L);

            assertThat(result).isEqualTo(StreamRecorder.StopOutcome.NO_PROCESS);
            assertThat(stopRequestedOf(recorder)).doesNotContain("video001");
        }

        @Test
        @DisplayName("正常系：追跡中の録画は、止めた印を付けてから、出力先で探したyt-dlpを子孫ごと止める")
        void testMethod03(@TempDir Path tempDir) {
            // 印を止めた後に付けると、その隙に録り直しが始まりうる
            StreamRecorder recorder = newRecorder(tempDir);
            Recording recording = recordingWithStatus(Recording.RecordingStatus.RECORDING);
            when(recordingHistoryService.findById(100L)).thenReturn(recording);
            activeRecordingsOf(recorder).add("video001");
            // 別スレッドで止めるので、handle はスタブしない（消費がテストの終わりに間に合わないことがある）
            ProcessHandle handle = mock(ProcessHandle.class);
            when(processLauncher.findYtDlpProcessesWithCommandLineContaining("UCxxxxxxxx/video001.%(ext)s"))
                    .thenAnswer(invocation -> {
                        assertThat(stopRequestedOf(recorder)).contains("video001");
                        return List.of(handle);
                    });

            StreamRecorder.StopOutcome result = recorder.stopRecording(100L);

            assertThat(result).isEqualTo(StreamRecorder.StopOutcome.STOPPING);
            // 止めるのは別の仮想スレッド
            verify(handle, timeout(5000)).destroy();
        }

        @Test
        @DisplayName("正常系：巡回の合図を待っている録画を止めると、待ちをすぐに解き、録り直さずに失敗として記録する")
        void testMethod04(@TempDir Path tempDir) throws Exception {
            // 合図を待つ録画には止める yt-dlp が無い。待ちを解かないと、合図の待ち（ここでは 60 秒 × 3）が切れるまで録画中のまま残る
            StreamRecorder recorder = newRecorder(tempDir, 1080, 60);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Recording recording = recordingWithStatus(Recording.RecordingStatus.RECORDING);
            when(recordingHistoryService.findById(100L)).thenReturn(recording);
            // 1 回目もその場の録り直しも、すぐに終わって再生できるファイルを残さない（その後に合図を待つ）
            Process process = exitingProcess();
            Process retry = exitingProcess();
            when(processLauncher.launch(eq(FALLBACK_COMMAND), any())).thenReturn(retry);
            when(recordingSalvager.ensurePlayable(any(Path.class))).thenReturn(SalvageOutcome.unavailable());
            activeRecordingsOf(recorder).add("video001");
            Map<String, ?> liveConfirmations = (Map<String, ?>) ReflectionTestUtils.getField(recorder, "liveConfirmations");

            Thread worker = Thread.ofVirtual().start(() -> recorder.awaitCompletion(
                    process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null));
            // 合図を待ち始めてから止める。先に止めると、合図を待つ前の印の確認で録り直しをやめ、待ちを解く処理を通らない
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!liveConfirmations.containsKey("video001")) {
                assertThat(worker.join(Duration.ofMillis(20))).as("合図を待つ前に完了待ちが終わった").isFalse();
                assertThat(System.nanoTime()).as("完了待ちが 10 秒で合図の待ちに入らない").isLessThan(deadline);
            }
            StreamRecorder.StopOutcome result = recorder.stopRecording(100L);

            assertThat(result).isEqualTo(StreamRecorder.StopOutcome.STOPPING);
            assertThat(worker.join(Duration.ofSeconds(10))).as("止めた後も合図を待ち続けている").isTrue();
            // その場の 1 回だけ。待ちを解いたのが止めた操作なので、合図による録り直しには進まない
            verify(processLauncher, times(1)).launch(eq(FALLBACK_COMMAND), any());
            verify(recordingHistoryService).markFailed(100L);
            assertThat(stopRequestedOf(recorder)).doesNotContain("video001");
            assertThat(recorder.isRecording("video001")).isFalse();
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
            when(mockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(mockProcess.exitValue()).thenReturn(exitCode);
            return mockProcess;
        }

        /**
         * 自分では終わらない録画プロセスを模す。{@code waitFor(1, 分)} が常に false を返すので、
         * 完了待ちは 1 回目の見回りで固まり・空き容量を判定する。止めた後の {@code waitFor()} は既定の 0 を返す。
         *
         * <p>{@code handle} は {@code mock(ProcessHandle.class)} のままでよい。Mockito 5 の既定の応答では、
         * {@code descendants()} は空の Stream を、{@code onExit()} は完了済みの CompletableFuture を返す。
         * そのため {@code ProcessTermination.terminateTreeAndAwait} はすぐに終わる。
         */
        private Process processNotExiting(ProcessHandle handle) throws Exception {
            Process process = mock(Process.class);
            when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
            when(process.toHandle()).thenReturn(handle);
            return process;
        }

        /**
         * 完了待ちを別スレッドで動かし、終わるまで巡回の「まだ配信中」の合図を送り続ける。
         * 合図は、待っている録画スレッドが無ければ何もしない（{@code confirmStillLive} の JavaDoc）。そのため送り続けてよく、
         * 「待ち始めた瞬間」を掴むための sleep も要らない。
         */
        private void awaitCompletionWhileConfirming(StreamRecorder recorder, Process process, Path tempDir)
                throws InterruptedException {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Thread worker = Thread.ofVirtual().start(() -> recorder.awaitCompletion(
                    process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!worker.join(Duration.ofMillis(20))) {
                recorder.confirmStillLive("video001");
                assertThat(System.nanoTime()).as("完了待ちが 10 秒で終わらない").isLessThan(deadline);
            }
        }

        @Test
        @DisplayName("正常系：正常終了(exitCode=0)し完成ファイルが存在する場合は録画完了として記録される")
        void testMethod01(@TempDir Path tempDir) throws Exception {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            when(mockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(mockProcess.exitValue()).thenReturn(0);
            activeRecordingsOf(recorder).add("video001");
            stubFileExists(12345L);

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

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

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

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

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

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

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

            verify(recordingHistoryService).markFailed(100L);
            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
        }

        @Test
        @DisplayName("異常系：完了待ちが割り込まれ完成ファイルも無い場合は録画失敗として記録される")
        void testMethod05(@TempDir Path tempDir) throws Exception {
            StreamRecorder recorder = newRecorder(tempDir);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process mockProcess = mock(Process.class);
            when(mockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
            activeRecordingsOf(recorder).add("video001");
            stubFileMissing();

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

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
            when(mockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
            activeRecordingsOf(recorder).add("video001");
            stubFileExists(555L);

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

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

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

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

            recorder.awaitCompletion(mockProcess, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

            InOrder inOrder = inOrder(mockProcess, recordingSalvager);
            inOrder.verify(mockProcess).waitFor(anyLong(), any(TimeUnit.class));
            inOrder.verify(recordingSalvager).ensurePlayable(any(Path.class));
        }

        @Test
        @DisplayName("正常系：最初からの録画が再生できるファイルを残さなければ、今の時点から録り直し、録れたものを「途中まで」として記録する")
        void testMethod09(@TempDir Path tempDir) throws Exception {
            // Twitch でアーカイブがサブスク限定のチャンネルは --live-from-start だと必ず失敗するが、配信そのものは録れる
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = processExiting(1);
            Process retry = processExiting(0);
            when(processLauncher.launch(eq(FALLBACK_COMMAND), any())).thenReturn(retry);
            when(recordingSalvager.ensurePlayable(any(Path.class)))
                    .thenReturn(SalvageOutcome.unavailable(), new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, 500L));
            activeRecordingsOf(recorder).add("video001");

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);

            verify(processLauncher).launch(eq(FALLBACK_COMMAND), any());
            // 配信の最初からは録れていないので、完了とは区別する
            verify(recordingHistoryService).markPartial(100L, 500L);
            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
            assertThat(recorder.isRecording("video001")).isFalse();
            // 途中までは録れているので知らせない
            verify(discordNotifier, never()).sendAdminAlert(any());
        }

        @Test
        @DisplayName("異常系：その場の録り直しも失敗し、巡回から配信中の合図が来なければ、それ以上録り直さず失敗として記録する")
        void testMethod10(@TempDir Path tempDir) throws Exception {
            // 配信が終わっていれば「今の時点から」は失敗するか、アーカイブ全体を落とし始める
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = processExiting(1);
            Process retry = processExiting(1);
            when(processLauncher.launch(eq(FALLBACK_COMMAND), any())).thenReturn(retry);
            stubFileMissing();
            activeRecordingsOf(recorder).add("video001");

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);

            verify(processLauncher, times(1)).launch(eq(FALLBACK_COMMAND), any());
            verify(recordingHistoryService).markFailed(100L);
            assertThat(recorder.isRecording("video001")).isFalse();
        }

        @Test
        @DisplayName("正常系：巡回から配信中の合図が来れば、今の時点からもう一度録り直す")
        void testMethod11(@TempDir Path tempDir) throws Exception {
            // 配信開始の直後は、YouTube の一時的な拒否で 1 回目もその場の録り直しも数秒で失敗することがある
            StreamRecorder recorder = newRecorder(tempDir, 1080, 60);
            Process process = processExiting(1);
            Process retry = processExiting(1);
            when(processLauncher.launch(eq(FALLBACK_COMMAND), any())).thenReturn(retry);
            when(recordingSalvager.ensurePlayable(any(Path.class))).thenReturn(
                    SalvageOutcome.unavailable(), SalvageOutcome.unavailable(),
                    new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, 700L));

            awaitCompletionWhileConfirming(recorder, process, tempDir);

            verify(processLauncher, times(2)).launch(eq(FALLBACK_COMMAND), any());
            verify(recordingHistoryService).markPartial(100L, 700L);
            verify(recordingHistoryService, never()).markFailed(any());
        }

        @Test
        @DisplayName("異常系：合図が来続けても、録り直しは上限（その場の1回と合図による2回）で止めて失敗として記録する")
        void testMethod12(@TempDir Path tempDir) throws Exception {
            // 待機所の誤検知のような直らない失敗で、配信中と判定され続ける限り録り直し続けないため
            StreamRecorder recorder = newRecorder(tempDir, 1080, 60);
            Process process = processExiting(1);
            Process retry = processExiting(1);
            when(processLauncher.launch(eq(FALLBACK_COMMAND), any())).thenReturn(retry);
            stubFileMissing();

            awaitCompletionWhileConfirming(recorder, process, tempDir);

            // その場の 1 回 + MAX_LIVE_RETRIES(2)
            verify(processLauncher, times(3)).launch(eq(FALLBACK_COMMAND), any());
            verify(recordingHistoryService, times(1)).markFailed(100L);
        }

        @Test
        @DisplayName("異常系：完了待ちが割り込まれたときは録り直さず、結合途中のファイルも消さない")
        void testMethod13(@TempDir Path tempDir) throws Exception {
            // 割り込まれるのはアプリの停止など。終了コードが取れないときはプロセスがまだ動いているかもしれない
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = mock(Process.class);
            when(process.waitFor(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
            stubFileMissing();
            Path leftover = Files.writeString(tempDir.resolve("video001.temp.mp4"), "結合途中");

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);
            // ほかの確認より先にフラグを消す。途中の確認が落ちて割り込み状態が残ると、同じスレッドで走る後のテストまで落ちる
            boolean interrupted = Thread.interrupted();

            assertThat(interrupted).isTrue();
            verify(processLauncher, never()).launch(any(), any());
            verify(recordingHistoryService).markFailed(100L);
            assertThat(leftover).exists();
        }

        @Test
        @DisplayName("異常系：出力が止まった録画は子孫ごと止め、録り直さずに失敗として記録し、管理者へ知らせる")
        void testMethod14(@TempDir Path tempDir) throws Exception {
            // 止めないと RECORDING が残り続け、その動画 ID は予約に押さえられたまま録り直せない。
            // 配信中でも同じ止まり方を繰り返しうるので録り直さない
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            ReflectionTestUtils.setField(recorder, "stallMinutes", 0L);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            ProcessHandle handle = mock(ProcessHandle.class);
            Process process = processNotExiting(handle);
            stubFileMissing();
            activeRecordingsOf(recorder).add("video001");

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);

            verify(handle).destroy();
            verify(processLauncher, never()).launch(any(), any());
            verify(recordingHistoryService).markFailed(100L);
            // 失敗の通知も届くので、件数は文言で絞る
            verify(discordNotifier, times(1)).sendAdminAlert(contains("止まっていた"));
            assertThat(recorder.isRecording("video001")).isFalse();
        }

        @Test
        @DisplayName("異常系：録画中に空き容量が下限を割ったら子孫ごと止め、結合途中のファイルを消し、録り直さない")
        void testMethod15(@TempDir Path tempDir) throws Exception {
            // 0 まで減ると、同じファイルシステムにある H2 の書き込みも失敗して監視・通知まで止まる
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            // 100 万 GB。下限（その 1/4）もどの機械の空きも下回る。固まりの判定は既定の 30 分のまま
            ReflectionTestUtils.setField(recorder, "minFreeGb", 1_000_000L);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            ProcessHandle handle = mock(ProcessHandle.class);
            Process process = processNotExiting(handle);
            Path leftover = Files.writeString(tempDir.resolve("video001.temp.mp4"), "結合途中");
            stubFileMissing();

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);

            verify(handle).destroy();
            assertThat(leftover).doesNotExist();
            verify(processLauncher, never()).launch(any(), any());
            verify(recordingHistoryService).markFailed(100L);
            verify(discordNotifier, times(1)).sendAdminAlert(contains("空き容量"));
        }

        @Test
        @DisplayName("異常系：空き容量が足りずに詰め替えを見送ったときは、録り直さずに失敗として記録する")
        void testMethod16(@TempDir Path tempDir) throws Exception {
            // データは残っており、空きができた後の後始末で直せる。録り直すとさらに書き込む
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = processExiting(1);
            when(recordingSalvager.ensurePlayable(any(Path.class))).thenReturn(SalvageOutcome.insufficientSpace());

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);

            verify(processLauncher, never()).launch(any(), any());
            verify(recordingHistoryService).markFailed(100L);
        }

        @Test
        @DisplayName("異常系：録画履歴の行が消えていれば（チャンネルの削除）、詰め替えも録り直しも記録もせず、予約だけ外す")
        void testMethod17(@TempDir Path tempDir) throws Exception {
            // 止めた yt-dlp は完成ファイルを残さないことが多く、そのままでは録り直して削除したチャンネルを録り続ける
            // （1 時間で 6.9GB を書いた実例）
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            when(recordingHistoryService.exists(100L)).thenReturn(false);
            Process process = processExiting(1);
            activeRecordingsOf(recorder).add("video001");

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);

            verify(recordingSalvager, never()).ensurePlayable(any());
            verify(processLauncher, never()).launch(any(), any());
            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markPartial(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
            verify(discordNotifier, never()).sendAdminAlert(any());
            assertThat(recorder.isRecording("video001")).isFalse();
        }

        @Test
        @DisplayName("異常系：録り直しを起動する直前に行が消えていれば、録り直さず記録もしない")
        void testMethod18(@TempDir Path tempDir) throws Exception {
            // 詰め替え（最大 600 秒）の間に削除されると、削除時の停止処理はまだ起動していない録り直しを止められない
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = processExiting(1);
            // 1 回目の終了直後・その場の録り直しの直前
            when(recordingHistoryService.exists(100L)).thenReturn(true, false);
            stubFileMissing();
            activeRecordingsOf(recorder).add("video001");

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);

            verify(processLauncher, never()).launch(any(), any());
            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markPartial(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
            assertThat(recorder.isRecording("video001")).isFalse();
        }

        @Test
        @DisplayName("異常系：再生できるファイルが残らず失敗として記録したときは、管理者へ知らせる")
        void testMethod19(@TempDir Path tempDir) throws Exception {
            // yt-dlp は YouTube 側の変更で壊れやすく、壊れると以降の録画がすべて失敗する。気付くのが遅れるほど取り返せない
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = processExiting(1);
            stubFileMissing();

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

            verify(recordingHistoryService).markFailed(100L);
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(discordNotifier, times(1)).sendAdminAlert(message.capture());
            assertThat(message.getValue()).contains("video001", "録画に失敗しました");
        }

        @Test
        @DisplayName("正常系：録画が完了したときは管理者へ知らせない")
        void testMethod20(@TempDir Path tempDir) throws Exception {
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = processExiting(0);
            stubFileExists(100L);

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

            verify(recordingHistoryService).markCompleted(100L, 100L);
            verify(discordNotifier, never()).sendAdminAlert(any());
        }

        @Test
        @DisplayName("異常系：結果の記録で例外が出ても、外へ投げずに予約を外す")
        void testMethod21(@TempDir Path tempDir) throws Exception {
            // 予約が残ると、その動画 ID は以降録画できず、RecordingReconciler も補正できない
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = processExiting(1);
            stubFileMissing();
            doThrow(new IllegalStateException("DB障害")).when(recordingHistoryService).markFailed(100L);
            activeRecordingsOf(recorder).add("video001");

            assertThatCode(() -> recorder.awaitCompletion(
                    process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null))
                    .doesNotThrowAnyException();

            assertThat(recorder.isRecording("video001")).isFalse();
        }

        @Test
        @DisplayName("異常系：管理画面から止めた録画は録り直さずに記録し、止めた印を外す")
        void testMethod22(@TempDir Path tempDir) throws Exception {
            // 録り直すと、止めたはずの配信を録り続ける
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            stopRequestedOf(recorder).add("video001");
            Process process = processExiting(1);
            stubFileMissing();
            activeRecordingsOf(recorder).add("video001");

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);

            verify(processLauncher, never()).launch(any(), any());
            verify(recordingHistoryService).markFailed(100L);
            assertThat(stopRequestedOf(recorder)).doesNotContain("video001");
            assertThat(recorder.isRecording("video001")).isFalse();
        }

        @Test
        @DisplayName("異常系：合図を待つ間に行が消えていれば、合図が来なくても失敗を記録しない")
        void testMethod23(@TempDir Path tempDir) throws Exception {
            // 削除したチャンネルは巡回されず合図が来ないので、待ちが切れた後に消えた行へ失敗を記録しないこと
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = processExiting(1);
            Process retry = processExiting(1);
            when(processLauncher.launch(eq(FALLBACK_COMMAND), any())).thenReturn(retry);
            stubFileMissing();
            // 1 回目の終了直後・その場の録り直しの直前・その終了直後・合図を待った後
            when(recordingHistoryService.exists(100L)).thenReturn(true, true, true, false);
            activeRecordingsOf(recorder).add("video001");

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), FALLBACK_COMMAND, null);

            // その場の 1 回だけ
            verify(processLauncher, times(1)).launch(eq(FALLBACK_COMMAND), any());
            verify(recordingHistoryService, never()).markCompleted(any(), anyLong());
            verify(recordingHistoryService, never()).markPartial(any(), anyLong());
            verify(recordingHistoryService, never()).markFailed(any());
            // 記録しないので知らせない
            verify(discordNotifier, never()).sendAdminAlert(any());
            assertThat(recorder.isRecording("video001")).isFalse();
        }

        @Test
        @DisplayName("正常系：録画プロセスが自然に終わったときも、結合途中のファイルを詰め替えより先に消す")
        void testMethod24(@TempDir Path tempDir) throws Exception {
            // 配信が終わった後の結合が空きを使い切って失敗すると、書きかけが空きを塞ぎ続け、詰め替えも始められない
            StreamRecorder recorder = newRecorder(tempDir, 1080, 0);
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            Process process = processExiting(1);
            Path leftover = Files.writeString(tempDir.resolve("video001.temp.mp4"), "結合途中");
            when(recordingSalvager.ensurePlayable(any(Path.class))).thenAnswer(invocation -> {
                // 消すのが詰め替えより前であること
                assertThat(leftover).doesNotExist();
                return new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, 100L);
            });

            recorder.awaitCompletion(process, channel, "video001", 100L, tempDir.resolve("video001.mp4"), null, null);

            assertThat(leftover).doesNotExist();
            verify(recordingHistoryService).markCompleted(100L, 100L);
        }
    }
}
