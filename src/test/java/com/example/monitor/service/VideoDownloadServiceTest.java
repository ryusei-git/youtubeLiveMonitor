package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.dto.DownloadResponse;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.exception.InsufficientDiskSpaceException;
import com.example.monitor.exception.LiveStreamDownloadRejectedException;
import com.example.monitor.exception.VideoAlreadyDownloadedException;
import com.example.monitor.notification.DiscordNotifier;
import com.example.monitor.platform.Platform;
import com.example.monitor.platform.StreamPlatform;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoDownloadService")
// ArgumentCaptor.forClass(List.class) は総称型の情報を渡せず未検査キャストになる（StreamRecorderTest と同じ）
@SuppressWarnings("unchecked")
class VideoDownloadServiceTest {

    private static final String YOUTUBE_URL = "https://www.youtube.com/watch?v=aqz-KE-bpKQ";

    @Mock
    private StreamPlatformRegistry streamPlatformRegistry;

    @Mock
    private StreamPlatform streamPlatform;

    @Mock
    private VideoSourceProbe videoSourceProbe;

    @Mock
    private ProcessLauncher processLauncher;

    @Mock
    private RecordingHistoryService recordingHistoryService;

    @Mock
    private RecordingSalvager recordingSalvager;

    @Mock
    private RecordingFileService recordingFileService;

    @Mock
    private RecordingRepository recordingRepository;

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private AuditLogger auditLogger;

    @BeforeEach
    void stubAsyncCompletionPath() {
        // 完了待ちは別の仮想スレッドで動くため、テストスレッドの進行とは無関係に
        // 消費される（消費されないこともある）。検証対象ではないので lenient にする
        lenient().when(recordingHistoryService.recordStart(any(), any(), any(), any()))
                .thenReturn(Recording.builder().id(1L).build());
        lenient().when(recordingSalvager.ensurePlayable(any())).thenReturn(SalvageOutcome.unavailable());
        // 行が無いと完了待ちは記録を飛ばす（チャンネル削除時の扱い）。通常は行がある
        lenient().when(recordingHistoryService.exists(any())).thenReturn(true);
    }

    /**
     * 起動直後に終わるプロセスを模す。完了待ちは {@code waitFor(1, 分)} を繰り返すため、
     * スタブしないと既定の {@code false} で空回りし続ける。テストより先に終わることもあるので lenient にする。
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

    private VideoDownloadService newService(Path recordingDirectory) {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties("", ""),
                new DiscordProperties(""),
                new RecordingProperties(recordingDirectory.toString(), 0),
                new MonitorProperties.AdminProperties("admin", ""));
        return new VideoDownloadService(properties, streamPlatformRegistry, videoSourceProbe,
                processLauncher, recordingHistoryService, recordingSalvager, recordingFileService,
                recordingRepository, monitoredChannelRepository, appUserRepository, auditLogger,
                new ActiveVideoJobs(), mock(DiscordNotifier.class));
    }

    /**
     * 予約機構を共有した状態のサービスを作る。
     *
     * <p>自動録画と手動ダウンロードは<b>同じ {@link ActiveVideoJobs} を共有している</b>。
     * 別々の集合を持たせると同時開始を防げないため、その共有が効いていることを
     * 確かめるにはこちらを使う。
     *
     * @param recordingDirectory 保存先
     * @param activeVideoJobs    共有する予約機構
     * @return サービス
     */
    private VideoDownloadService newService(Path recordingDirectory, ActiveVideoJobs activeVideoJobs) {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties("", ""),
                new DiscordProperties(""),
                new RecordingProperties(recordingDirectory.toString(), 0),
                new MonitorProperties.AdminProperties("admin", ""));
        return new VideoDownloadService(properties, streamPlatformRegistry, videoSourceProbe,
                processLauncher, recordingHistoryService, recordingSalvager, recordingFileService,
                recordingRepository, monitoredChannelRepository, appUserRepository, auditLogger,
                activeVideoJobs, mock(DiscordNotifier.class));
    }

    /**
     * 配信の状態を指定して、YouTube の動画 1 本が見つかる状態を作る。
     *
     * @param liveStatus {@code yt-dlp} が返す {@code live_status}
     */
    private void givenYouTubeVideoWithLiveStatus(String liveStatus) {
        when(streamPlatformRegistry.findByUrl(YOUTUBE_URL)).thenReturn(streamPlatform);
        VideoSource source = new VideoSource(
                "youtube", "aqz-KE-bpKQ", "UCSMOQeBJ2RAnuFungnQOxLg", "@BlenderOfficial",
                liveStatus, "Big Buck Bunny");
        when(videoSourceProbe.probe(YOUTUBE_URL)).thenReturn(Optional.of(source));
        lenient().when(streamPlatform.resolveChannelId(source))
                .thenReturn(Optional.of("UCSMOQeBJ2RAnuFungnQOxLg"));
        lenient().when(streamPlatform.platform()).thenReturn(Platform.YOUTUBE);
    }

    private Set<String> activeDownloadsOf(VideoDownloadService service) {
        return (Set<String>) ReflectionTestUtils.getField(ReflectionTestUtils.getField(service, "activeVideoJobs"), "reservedVideoIds");
    }

    /**
     * YouTube の動画 1 本が見つかる状態を作る。
     *
     * @param channelId メタデータから得られるチャンネル ID。特定できない場合は {@code null}
     */
    private void givenYouTubeVideo(String channelId) {
        when(streamPlatformRegistry.findByUrl(YOUTUBE_URL)).thenReturn(streamPlatform);
        VideoSource source = new VideoSource(
                "youtube", "aqz-KE-bpKQ", channelId, "@BlenderOfficial", "not_live", "Big Buck Bunny");
        when(videoSourceProbe.probe(YOUTUBE_URL)).thenReturn(Optional.of(source));
        // 重複を弾くケースではここまで進まないため lenient にする
        lenient().when(streamPlatform.resolveChannelId(source)).thenReturn(Optional.ofNullable(channelId));
        lenient().when(streamPlatform.platform()).thenReturn(Platform.YOUTUBE);
    }

    @Nested
    @DisplayName("startDownload()")
    class StartDownload {

        @Test
        @DisplayName("正常系：登録済みチャンネルの動画はそのチャンネルに紐づけて記録する")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            MonitoredChannel channel = new MonitoredChannel("UCSMOQeBJ2RAnuFungnQOxLg", "Blender");
            when(monitoredChannelRepository.findByYoutubeChannelId("UCSMOQeBJ2RAnuFungnQOxLg"))
                    .thenReturn(Optional.of(channel));
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            DownloadResponse response = newService(tempDir).startDownload(YOUTUBE_URL);

            assertThat(response.videoId()).isEqualTo("aqz-KE-bpKQ");
            assertThat(response.title()).isEqualTo("Big Buck Bunny");
            assertThat(response.channelName()).isEqualTo("Blender");
            assertThat(response.filePath()).isEqualTo("UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.mp4");
            verify(recordingHistoryService).recordStart(
                    eq(channel), eq("aqz-KE-bpKQ"), eq("Big Buck Bunny"),
                    eq("UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.mp4"));
        }

        @Test
        @DisplayName("正常系：未登録チャンネルの動画はチャンネルに紐づけずに記録する")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            // 紐づけたいがために勝手に監視対象へ登録はしない（意図しない通知・自動録画が始まるため）
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(monitoredChannelRepository.findByYoutubeChannelId("UCSMOQeBJ2RAnuFungnQOxLg"))
                    .thenReturn(Optional.empty());
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            DownloadResponse response = newService(tempDir).startDownload(YOUTUBE_URL);

            assertThat(response.channelName()).isNull();
            assertThat(response.channelId()).isEqualTo("UCSMOQeBJ2RAnuFungnQOxLg");
            verify(recordingHistoryService).recordStart(
                    isNull(), eq("aqz-KE-bpKQ"), eq("Big Buck Bunny"),
                    eq("UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.mp4"));
        }

        @Test
        @DisplayName("正常系：チャンネルを特定できない場合は専用ディレクトリに保存する")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            // Twitch の VOD のように channel_id もログイン名も解決できない場合
            givenYouTubeVideo(null);
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            DownloadResponse response = newService(tempDir).startDownload(YOUTUBE_URL);

            assertThat(response.channelId()).isNull();
            assertThat(response.filePath()).isEqualTo("downloads/aqz-KE-bpKQ.mp4");
            // 特定できないのだから、チャンネルを引きに行くこともしない
            verify(monitoredChannelRepository, never()).findByYoutubeChannelId(any());
        }

        @Test
        @DisplayName("正常系：ライブ録画用の指定を付けずにyt-dlpを起動する")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(monitoredChannelRepository.findByYoutubeChannelId(any())).thenReturn(Optional.empty());
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            newService(tempDir).startDownload(YOUTUBE_URL);

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processLauncher).launch(captor.capture(), any());
            List<String> command = captor.getValue();

            assertThat(command).first().isEqualTo("yt-dlp");
            assertThat(command).contains("--merge-output-format", "mp4");
            // 既に完結した動画が相手なので、ライブ配信向けの指定は付けない
            assertThat(command).doesNotContain("--live-from-start");
            // 再生リスト付きのURLを貼られても1本だけを対象にする
            assertThat(command).contains("--no-playlist");
            assertThat(command).last().isEqualTo(YOUTUBE_URL);
        }

        @Test
        @DisplayName("正常系：保存先ディレクトリを作成する")
        void testMethod05(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(monitoredChannelRepository.findByYoutubeChannelId(any())).thenReturn(Optional.empty());
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            newService(tempDir).startDownload(YOUTUBE_URL);

            assertThat(Files.isDirectory(tempDir.resolve("UCSMOQeBJ2RAnuFungnQOxLg"))).isTrue();
        }

        @Test
        @DisplayName("正常系：タイトルを取得できなかった場合は動画IDで代用する")
        void testMethod06(@TempDir Path tempDir) throws IOException {
            when(streamPlatformRegistry.findByUrl(YOUTUBE_URL)).thenReturn(streamPlatform);
            VideoSource source = new VideoSource("youtube", "aqz-KE-bpKQ", null, null, "not_live", null);
            when(videoSourceProbe.probe(YOUTUBE_URL)).thenReturn(Optional.of(source));
            when(streamPlatform.resolveChannelId(source)).thenReturn(Optional.empty());
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            DownloadResponse response = newService(tempDir).startDownload(YOUTUBE_URL);

            assertThat(response.title()).isEqualTo("aqz-KE-bpKQ");
        }

        @Test
        @DisplayName("異常系：同じ動画の録画履歴が既にある場合は弾く")
        void testMethod07(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(recordingRepository.existsByVideoId("aqz-KE-bpKQ")).thenReturn(true);

            assertThatThrownBy(() -> newService(tempDir).startDownload(YOUTUBE_URL))
                    .isInstanceOf(VideoAlreadyDownloadedException.class)
                    .hasMessageContaining("aqz-KE-bpKQ");

            // 同じ出力先へ二重に書き込ませない
            verify(processLauncher, never()).launch(any(), any());
            verify(recordingHistoryService, never()).recordStart(any(), any(), any(), any());
        }

        @Test
        @DisplayName("異常系：同じ動画のダウンロードが進行中の場合も弾く")
        void testMethod08(@TempDir Path tempDir) throws IOException {
            // DB の履歴だけを見ると、確認と登録の間に別のリクエストが入り込める
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            VideoDownloadService service = newService(tempDir);
            activeDownloadsOf(service).add("aqz-KE-bpKQ");

            assertThatThrownBy(() -> service.startDownload(YOUTUBE_URL))
                    .isInstanceOf(VideoAlreadyDownloadedException.class);

            verify(processLauncher, never()).launch(any(), any());
        }

        @Test
        @DisplayName("異常系：対応していないURLはそのまま例外を伝える")
        void testMethod09(@TempDir Path tempDir) {
            when(streamPlatformRegistry.findByUrl("https://example.com/video/1"))
                    .thenThrow(new IllegalArgumentException("対応していないURLです"));

            assertThatThrownBy(() -> newService(tempDir).startDownload("https://example.com/video/1"))
                    .isInstanceOf(IllegalArgumentException.class);

            // 対応していないと分かった時点で打ち切る（yt-dlp を起動しない）
            verify(videoSourceProbe, never()).probe(any());
        }

        @Test
        @DisplayName("異常系：動画の情報を取得できない場合は例外を投げる")
        void testMethod10(@TempDir Path tempDir) throws IOException {
            when(streamPlatformRegistry.findByUrl(YOUTUBE_URL)).thenReturn(streamPlatform);
            when(videoSourceProbe.probe(YOUTUBE_URL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> newService(tempDir).startDownload(YOUTUBE_URL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(YOUTUBE_URL);

            verify(processLauncher, never()).launch(any(), any());
        }

        @Test
        @DisplayName("異常系：URLが空の場合は例外を投げる")
        void testMethod11(@TempDir Path tempDir) {
            assertThatThrownBy(() -> newService(tempDir).startDownload("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系：yt-dlpを起動できない場合は例外を投げ、再試行できる状態に戻す")
        void testMethod12(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(monitoredChannelRepository.findByYoutubeChannelId(any())).thenReturn(Optional.empty());
            when(processLauncher.launch(any(), any())).thenThrow(new IOException("yt-dlp: command not found"));
            VideoDownloadService service = newService(tempDir);

            assertThatThrownBy(() -> service.startDownload(YOUTUBE_URL))
                    .isInstanceOf(IllegalStateException.class);

            // 登録を残すと、この動画は以降永久にダウンロードできなくなる
            assertThat(service.isDownloading("aqz-KE-bpKQ")).isFalse();
        }

        @Test
        @DisplayName("異常系：自動録画中の動画はダウンロードを開始できない")
        void testMethod13(@TempDir Path tempDir) throws IOException {
            // 同じ動画IDへ2つの yt-dlp が同じ出力先に書き込むと録画そのものが壊れる。
            // 録画側が先に予約を取っている状態を作る
            ActiveVideoJobs shared = new ActiveVideoJobs();
            shared.reserve("aqz-KE-bpKQ");
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            VideoDownloadService service = newService(tempDir, shared);

            assertThatThrownBy(() -> service.startDownload(YOUTUBE_URL))
                    .isInstanceOf(VideoAlreadyDownloadedException.class);

            verify(processLauncher, never()).launch(any(), any());
        }

        @Test
        @DisplayName("異常系：配信中のURLはプロセスを起動する前に拒否する")
        void testMethod14(@TempDir Path tempDir) throws IOException {
            // ライブ配信の取得は自動録画の担当。手動ダウンロードは VOD 向けと責務を分ける
            givenYouTubeVideoWithLiveStatus("is_live");
            VideoDownloadService service = newService(tempDir);

            assertThatThrownBy(() -> service.startDownload(YOUTUBE_URL))
                    .isInstanceOf(LiveStreamDownloadRejectedException.class);

            verify(processLauncher, never()).launch(any(), any());
            // 予約も取らない。取ったまま拒否すると、その動画は以降扱えなくなる
            assertThat(service.isDownloading("aqz-KE-bpKQ")).isFalse();
        }

        @Test
        @DisplayName("異常系：配信開始前の待機所のURLも拒否する")
        void testMethod15(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideoWithLiveStatus("is_upcoming");
            VideoDownloadService service = newService(tempDir);

            assertThatThrownBy(() -> service.startDownload(YOUTUBE_URL))
                    .isInstanceOf(LiveStreamDownloadRejectedException.class);

            verify(processLauncher, never()).launch(any(), any());
        }

        @Test
        @DisplayName("正常系：配信が終わった動画は拒否しない")
        void testMethod16(@TempDir Path tempDir) throws IOException {
            // was_live / post_live は既に配信が終わっている。自動録画と重なる場合は
            // 予約機構が弾くのが正しい層なので、ここでは拒否しない
            givenYouTubeVideoWithLiveStatus("was_live");
            when(monitoredChannelRepository.findByYoutubeChannelId(any())).thenReturn(Optional.empty());
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            assertThat(newService(tempDir).startDownload(YOUTUBE_URL)).isNotNull();

            verify(processLauncher).launch(any(), any());
        }

        @Test
        @DisplayName("正常系：配信直後の動画も拒否しない")
        void testMethod17(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideoWithLiveStatus("post_live");
            when(monitoredChannelRepository.findByYoutubeChannelId(any())).thenReturn(Optional.empty());
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            assertThat(newService(tempDir).startDownload(YOUTUBE_URL)).isNotNull();

            verify(processLauncher).launch(any(), any());
        }

        @Test
        @DisplayName("異常系：履歴の登録に失敗したら起動済みプロセスを停止して予約を解放する")
        void testMethod18(@TempDir Path tempDir) throws IOException, InterruptedException {
            // 起動済みのプロセスを放置すると、次の再試行で同じ出力先に別プロセスが起動する
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(monitoredChannelRepository.findByYoutubeChannelId(any())).thenReturn(Optional.empty());
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);
            when(recordingHistoryService.recordStart(any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("DB障害"));
            VideoDownloadService service = newService(tempDir);

            assertThatThrownBy(() -> service.startDownload(YOUTUBE_URL))
                    .isInstanceOf(IllegalStateException.class);

            verify(process).destroyForcibly();
            // destroyForcibly() だけでは終わらない。実際に終了したことを確認してから解放する
            verify(process).waitFor();
            assertThat(service.isDownloading("aqz-KE-bpKQ")).isFalse();
        }

        @Test
        @DisplayName("異常系：失敗の履歴だけでも、空きが足りず詰め替えを見送った録画があれば、消さずに空き容量の例外を投げて予約を外す")
        void testMethod19(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(recordingRepository.existsByVideoId("aqz-KE-bpKQ")).thenReturn(true);
            Recording failed = Recording.builder().id(10L).videoId("aqz-KE-bpKQ")
                    .filePath("UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.mp4")
                    .status(Recording.RecordingStatus.FAILED).build();
            when(recordingRepository.findByVideoId("aqz-KE-bpKQ")).thenReturn(List.of(failed));
            when(recordingFileService.resolveFilePath(failed)).thenReturn(tempDir.resolve("aqz-KE-bpKQ.mp4"));
            when(recordingSalvager.lacksSpaceToSalvage(tempDir.resolve("aqz-KE-bpKQ.mp4"))).thenReturn(true);
            VideoDownloadService service = newService(tempDir);

            assertThatThrownBy(() -> service.startDownload(YOUTUBE_URL))
                    .isInstanceOf(InsufficientDiskSpaceException.class);

            // 消すと、空きができれば直せたはずの録画を失う
            verify(recordingHistoryService, never()).deleteRecording(any());
            verify(processLauncher, never()).launch(any(), any());
            // 予約が外れて、空きができればもう一度押せる
            assertThat(service.isDownloading("aqz-KE-bpKQ")).isFalse();
        }

        @Test
        @DisplayName("異常系：一般利用者の保存で詰め替えを見送った録画があれば、例外を投げて利用者の枠も外す")
        void testMethod20(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(recordingRepository.existsByVideoId("aqz-KE-bpKQ")).thenReturn(true);
            Recording failed = Recording.builder().id(10L).videoId("aqz-KE-bpKQ")
                    .filePath("UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.mp4")
                    .status(Recording.RecordingStatus.FAILED).build();
            when(recordingRepository.findByVideoId("aqz-KE-bpKQ")).thenReturn(List.of(failed));
            when(recordingFileService.resolveFilePath(failed)).thenReturn(tempDir.resolve("aqz-KE-bpKQ.mp4"));
            when(recordingSalvager.lacksSpaceToSalvage(tempDir.resolve("aqz-KE-bpKQ.mp4"))).thenReturn(true);
            AppUser alice = new AppUser("alice", "hashed-password", AppUser.Role.USER);
            alice.setId(5L);
            when(appUserRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
            VideoDownloadService service = newService(tempDir);

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("alice", "n/a", AuthorityUtils.NO_AUTHORITIES));
            try {
                assertThatThrownBy(() -> service.startDownload(YOUTUBE_URL))
                        .isInstanceOf(InsufficientDiskSpaceException.class);
            } finally {
                SecurityContextHolder.clearContext();
            }

            // 枠が残ると、この利用者は再起動まで保存できない
            Map<Long, String> slots = (Map<Long, String>) ReflectionTestUtils.getField(service, "userSlots");
            assertThat(slots).isEmpty();
            assertThat(service.isDownloading("aqz-KE-bpKQ")).isFalse();
        }

        @Test
        @DisplayName("異常系：失敗の履歴が2件で2件目だけ詰め替えを見送っていても、1件目も消さない")
        void testMethod21(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(recordingRepository.existsByVideoId("aqz-KE-bpKQ")).thenReturn(true);
            Recording first = Recording.builder().id(10L).videoId("aqz-KE-bpKQ")
                    .filePath("UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.mp4")
                    .status(Recording.RecordingStatus.FAILED).build();
            Recording second = Recording.builder().id(11L).videoId("aqz-KE-bpKQ")
                    .filePath("UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.f137.mp4")
                    .status(Recording.RecordingStatus.FAILED).build();
            when(recordingRepository.findByVideoId("aqz-KE-bpKQ")).thenReturn(List.of(first, second));
            when(recordingFileService.resolveFilePath(first)).thenReturn(tempDir.resolve("first.mp4"));
            when(recordingFileService.resolveFilePath(second)).thenReturn(tempDir.resolve("second.mp4"));
            when(recordingSalvager.lacksSpaceToSalvage(tempDir.resolve("first.mp4"))).thenReturn(false);
            when(recordingSalvager.lacksSpaceToSalvage(tempDir.resolve("second.mp4"))).thenReturn(true);
            VideoDownloadService service = newService(tempDir);

            assertThatThrownBy(() -> service.startDownload(YOUTUBE_URL))
                    .isInstanceOf(InsufficientDiskSpaceException.class);

            // すべて確かめ終える前に 1 件目を消していない
            verify(recordingHistoryService, never()).deleteRecording(any());
        }

        @Test
        @DisplayName("正常系：詰め替えを見送った録画が無ければ、失敗の履歴を消してからyt-dlpを起動する")
        void testMethod22(@TempDir Path tempDir) throws IOException {
            givenYouTubeVideo("UCSMOQeBJ2RAnuFungnQOxLg");
            when(recordingRepository.existsByVideoId("aqz-KE-bpKQ")).thenReturn(true);
            Recording failed = Recording.builder().id(10L).videoId("aqz-KE-bpKQ")
                    .filePath("UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.mp4")
                    .status(Recording.RecordingStatus.FAILED).build();
            when(recordingRepository.findByVideoId("aqz-KE-bpKQ")).thenReturn(List.of(failed));
            when(recordingFileService.resolveFilePath(failed)).thenReturn(tempDir.resolve("aqz-KE-bpKQ.mp4"));
            when(recordingSalvager.lacksSpaceToSalvage(tempDir.resolve("aqz-KE-bpKQ.mp4"))).thenReturn(false);
            when(monitoredChannelRepository.findByYoutubeChannelId(any())).thenReturn(Optional.empty());
            // exitedProcess() の中でもスタブするので、when(...) の途中で呼ばずに先に作る
            Process process = exitedProcess();
            when(processLauncher.launch(any(), any())).thenReturn(process);

            DownloadResponse response = newService(tempDir).startDownload(YOUTUBE_URL);

            assertThat(response.videoId()).isEqualTo("aqz-KE-bpKQ");
            InOrder order = inOrder(recordingHistoryService, processLauncher);
            order.verify(recordingHistoryService).deleteRecording(10L);
            order.verify(processLauncher).launch(any(), any());
        }
    }

    @Nested
    @DisplayName("awaitCompletion()")
    class AwaitCompletion {

        @Test
        @DisplayName("正常系：再生できるファイルが出来ていれば完了として記録する")
        void testMethod01(@TempDir Path tempDir) {
            VideoDownloadService service = newService(tempDir);
            Path outputFile = tempDir.resolve("downloads/video001.mp4");
            when(recordingSalvager.ensurePlayable(outputFile))
                    .thenReturn(new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, 12345L));

            service.awaitCompletion(exitedProcess(), 1L, "video001", outputFile, null, "(未登録)");

            verify(recordingHistoryService).markCompleted(1L, 12345L);
        }

        @Test
        @DisplayName("正常系：途中までのものは詰め替えた結果を「途中まで」として記録する")
        void testMethod02(@TempDir Path tempDir) {
            VideoDownloadService service = newService(tempDir);
            Path outputFile = tempDir.resolve("downloads/video001.mp4");
            when(recordingSalvager.ensurePlayable(outputFile))
                    .thenReturn(new SalvageOutcome(SalvageStatus.SALVAGED, 500L));

            service.awaitCompletion(exitedProcess(), 1L, "video001", outputFile, null, "(未登録)");

            verify(recordingHistoryService).markPartial(1L, 500L);
        }

        @Test
        @DisplayName("異常系：再生できるファイルを用意できなければ失敗として記録する")
        void testMethod03(@TempDir Path tempDir) {
            VideoDownloadService service = newService(tempDir);
            Path outputFile = tempDir.resolve("downloads/video001.mp4");
            when(recordingSalvager.ensurePlayable(outputFile)).thenReturn(SalvageOutcome.unavailable());

            service.awaitCompletion(exitedProcess(), 1L, "video001", outputFile, null, "(未登録)");

            verify(recordingHistoryService).markFailed(1L);
        }

        @Test
        @DisplayName("正常系：結果を記録し終えてから追跡を外す")
        void testMethod04(@TempDir Path tempDir) {
            // 順序が逆だと、その隙に RecordingReconciler が「置き去り」と誤判定する
            VideoDownloadService service = newService(tempDir);
            Path outputFile = tempDir.resolve("downloads/video001.mp4");
            activeDownloadsOf(service).add("video001");
            when(recordingSalvager.ensurePlayable(outputFile))
                    .thenReturn(new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, 1L));

            service.awaitCompletion(exitedProcess(), 1L, "video001", outputFile, null, "(未登録)");

            assertThat(service.isDownloading("video001")).isFalse();
        }
    }
}
