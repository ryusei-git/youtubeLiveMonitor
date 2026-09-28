package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.dto.DeviceDownloadResponse;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.Recording;
import com.example.monitor.exception.DeviceDownloadInProgressException;
import com.example.monitor.exception.DeviceDownloadNotFoundException;
import com.example.monitor.exception.LiveStreamDownloadRejectedException;
import com.example.monitor.exception.VideoAlreadyDownloadedException;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import com.example.monitor.util.YtDlpLogFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceDownloadService")
class DeviceDownloadServiceTest {

    private static final String URL = "https://www.youtube.com/watch?v=aqz-KE-bpKQ";
    private static final String VIDEO_ID = "aqz-KE-bpKQ";

    @Mock private StreamPlatformRegistry streamPlatformRegistry;
    @Mock private VideoSourceProbe videoSourceProbe;
    @Mock private ProcessLauncher processLauncher;
    @Mock private RecordingSalvager recordingSalvager;
    @Mock private RecordingRepository recordingRepository;
    @Mock private CurrentAppUser currentAppUser;
    @Mock private AuditLogger auditLogger;
    @Captor private ArgumentCaptor<List<String>> commandCaptor;

    /** 取得中のまま止めておく yt-dlp（{@code waitFor}）を放す合図。後片付けで必ず放す。 */
    private final CountDownLatch release = new CountDownLatch(1);
    private final AppUser alice = user(1L, "alice");
    private final AppUser bob = user(2L, "bob");

    /** 一時取得の置き場所（{@code <一時フォルダー>/device-downloads}）。テストの初めには無い。 */
    private Path root;
    private DeviceDownloadService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        root = tempDir.resolve("device-downloads");
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties("", ""),
                new DiscordProperties(""),
                new RecordingProperties(tempDir.resolve("recordings").toString(), 0),
                new MonitorProperties.AdminProperties("admin", ""));
        service = new DeviceDownloadService(properties, streamPlatformRegistry, videoSourceProbe,
                processLauncher, recordingSalvager, recordingRepository, currentAppUser, auditLogger);
        // @Value の項目はコンストラクタを通らない。空き容量のしきい値（minFreeGb）は既定の 0 のまま（見ない）
        ReflectionTestUtils.setField(service, "directory", root.toString());
        // 完了待ちは別の仮想スレッドで動き、消費のタイミングがテストと無関係なので lenient にする（docs/testing.md）
        lenient().when(recordingSalvager.ensurePlayable(any())).thenReturn(SalvageOutcome.unavailable());
        lenient().when(currentAppUser.require()).thenReturn(alice);
    }

    @AfterEach
    void releaseBlockedProcesses() {
        release.countDown();
    }

    private static AppUser user(Long id, String username) {
        AppUser user = new AppUser(username, "hashed-password", AppUser.Role.USER);
        user.setId(id);
        return user;
    }

    /** 下調べで、配信の状態が {@code liveStatus} の YouTube の動画 1 本が見つかる状態にする。 */
    private void givenVideo(String liveStatus) {
        VideoSource source = new VideoSource("youtube", VIDEO_ID, "UCSMOQeBJ2RAnuFungnQOxLg",
                "@BlenderOfficial", liveStatus, "Big Buck Bunny");
        when(videoSourceProbe.probe(URL)).thenReturn(Optional.of(source));
    }

    /** サービスに同じ動画の録画が {@code status} の状態である状態にする。 */
    private void givenServiceRecording(Recording.RecordingStatus status) {
        Recording recording = Recording.builder()
                .id(7L)
                .videoId(VIDEO_ID)
                .videoTitle("Big Buck Bunny（サービスの録画）")
                .filePath("UCSMOQeBJ2RAnuFungnQOxLg/" + VIDEO_ID + ".mp4")
                .status(status)
                .build();
        when(recordingRepository.findFirstByVideoId(VIDEO_ID)).thenReturn(Optional.of(recording));
    }

    /**
     * すぐ終わる yt-dlp を起動できる状態にする（{@code waitFor} は既定の 0 を返す）。
     * すぐ終わるので、{@code start()} の応答を作る前に完了待ちのスレッドが状態を書き換えることがある。
     * {@code start()} の応答で RUNNING を確かめるテストでは使わない。
     */
    private Process givenLaunchFinishes() throws IOException {
        Process process = mock(Process.class);
        when(processLauncher.launch(anyList(), any(Path.class))).thenReturn(process);
        return process;
    }

    /** {@link #release} を放すまで終わらない yt-dlp を起動できる状態にする（仕事は RUNNING のまま残る）。 */
    private Process givenLaunchBlocks() throws Exception {
        Process process = mock(Process.class);
        lenient().when(process.waitFor()).thenAnswer(invocation -> {
            release.await();
            return 0;
        });
        when(processLauncher.launch(anyList(), any(Path.class))).thenReturn(process);
        return process;
    }

    /** 仕事が RUNNING でなくなるまで、最大 5 秒待つ。完了待ちは別スレッドなので、終わるのを待ってから確かめる。 */
    private DeviceDownloadResponse awaitFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            DeviceDownloadResponse response = service.status(jobId);
            if (response.status() != DeviceDownloadResponse.Status.RUNNING) {
                return response;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("5 秒待っても取得が終わりませんでした: " + jobId);
    }

    /**
     * 止めたことを {@code destroy()} で確かめられる {@link ProcessHandle}。
     * {@code onExit()} を完了済みにしておかないと、{@code ProcessTermination.terminateTreeAndAwait} が
     * {@code onExit().get(...)} で NullPointerException になる（モックの既定値は null）。
     */
    private ProcessHandle stoppableHandle() {
        ProcessHandle handle = mock(ProcessHandle.class);
        when(handle.onExit()).thenReturn(CompletableFuture.completedFuture(handle));
        return handle;
    }

    @Nested
    @DisplayName("start()")
    class Start {

        @Test
        @DisplayName("正常系：受け付けると RUNNING の仕事を返し、仕事 ID のフォルダーへ出力する yt-dlp を起動して監査に残す")
        void testMethod01() throws Exception {
            givenVideo("not_live");
            givenLaunchBlocks();

            DeviceDownloadResponse response = service.start(URL);

            String jobId = response.jobId();
            assertThat(jobId).isNotNull();
            assertThat(response.status()).isEqualTo(DeviceDownloadResponse.Status.RUNNING);
            assertThat(response.videoId()).isEqualTo(VIDEO_ID);
            assertThat(response.title()).isEqualTo("Big Buck Bunny");
            assertThat(response.recordingId()).isNull();
            assertThat(response.fileUrl()).isNull();
            assertThat(root.resolve(jobId)).isDirectory();
            verify(processLauncher).launch(commandCaptor.capture(), eq(YtDlpLogFile.of(VIDEO_ID)));
            assertThat(commandCaptor.getValue())
                    .contains(root.resolve(jobId).resolve(VIDEO_ID + ".%(ext)s").toString(), URL);
            verify(auditLogger).record(eq(AuditAction.DOWNLOAD_REQUEST), eq(AuditOutcome.SUCCESS), eq(1L),
                    eq("alice"), any(), eq("DEVICE_DOWNLOAD"), eq(jobId), contains("destination=device"));
        }

        @Test
        @DisplayName("正常系：サービスに完成した録画（COMPLETED）があれば取り直さず、録画のファイルの場所を READY で返す")
        void testMethod02() throws Exception {
            givenVideo("not_live");
            givenServiceRecording(Recording.RecordingStatus.COMPLETED);

            DeviceDownloadResponse response = service.start(URL);

            assertThat(response.jobId()).isNull();
            assertThat(response.status()).isEqualTo(DeviceDownloadResponse.Status.READY);
            assertThat(response.videoId()).isEqualTo(VIDEO_ID);
            assertThat(response.title()).isEqualTo("Big Buck Bunny（サービスの録画）");
            assertThat(response.recordingId()).isEqualTo(7L);
            assertThat(response.fileUrl()).isEqualTo("/recordings/UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.mp4");
            verify(processLauncher, never()).launch(anyList(), any(Path.class));
            assertThat(service.list()).isEmpty();
            verify(auditLogger).record(eq(AuditAction.DOWNLOAD_REQUEST), eq(AuditOutcome.SUCCESS), eq(1L),
                    eq("alice"), any(), eq("RECORDING"), eq("7"), contains("destination=device"));
        }

        @Test
        @DisplayName("正常系：サービスの録画が途中まで（PARTIAL）なら渡さずに取り直す")
        void testMethod03() throws Exception {
            givenVideo("not_live");
            givenServiceRecording(Recording.RecordingStatus.PARTIAL);
            givenLaunchBlocks();

            DeviceDownloadResponse response = service.start(URL);

            assertThat(response.status()).isEqualTo(DeviceDownloadResponse.Status.RUNNING);
            assertThat(response.recordingId()).isNull();
            verify(processLauncher).launch(anyList(), any(Path.class));
        }

        @Test
        @DisplayName("異常系：サービスが同じ動画を録画中（RECORDING）なら VideoAlreadyDownloadedException で、起動せず枠も残さない")
        void testMethod04() throws Exception {
            givenVideo("not_live");
            givenServiceRecording(Recording.RecordingStatus.RECORDING);

            assertThatThrownBy(() -> service.start(URL)).isInstanceOf(VideoAlreadyDownloadedException.class);

            verify(processLauncher, never()).launch(anyList(), any(Path.class));
            assertThat(service.list()).isEmpty();
        }

        @Test
        @DisplayName("異常系：同じ利用者が取得中なら 2 件目は DeviceDownloadInProgressException で、動画の下調べもしない")
        void testMethod05() throws Exception {
            givenVideo("not_live");
            givenLaunchBlocks();
            String first = service.start(URL).jobId();

            assertThatThrownBy(() -> service.start(URL)).isInstanceOf(DeviceDownloadInProgressException.class);

            verify(videoSourceProbe, times(1)).probe(URL);
            assertThat(service.list()).extracting(DeviceDownloadResponse::jobId).containsExactly(first);
        }

        @Test
        @DisplayName("正常系：別の利用者の取得中は、自分の受け付けを妨げない")
        void testMethod06() throws Exception {
            givenVideo("not_live");
            givenLaunchBlocks();
            String aliceJobId = service.start(URL).jobId();
            when(currentAppUser.require()).thenReturn(bob);

            DeviceDownloadResponse bobResponse = service.start(URL);

            assertThat(bobResponse.status()).isEqualTo(DeviceDownloadResponse.Status.RUNNING);
            assertThat(bobResponse.jobId()).isNotEqualTo(aliceJobId);
            verify(processLauncher, times(2)).launch(anyList(), any(Path.class));
        }

        @Test
        @DisplayName("正常系：取得が終わった仕事があっても、次の動画を受け付ける")
        void testMethod07() throws Exception {
            givenVideo("not_live");
            givenLaunchFinishes();
            String first = service.start(URL).jobId();
            awaitFinished(first);

            DeviceDownloadResponse second = service.start(URL);

            assertThat(second.jobId()).isNotEqualTo(first);
        }

        @Test
        @DisplayName("異常系：配信中の URL は LiveStreamDownloadRejectedException で、起動せず枠も残さない")
        void testMethod08() throws Exception {
            givenVideo("is_live");

            assertThatThrownBy(() -> service.start(URL)).isInstanceOf(LiveStreamDownloadRejectedException.class);

            verify(processLauncher, never()).launch(anyList(), any(Path.class));
            assertThat(service.list()).isEmpty();
        }

        @Test
        @DisplayName("異常系：動画の情報を取れなければ IllegalArgumentException で、枠も残さない")
        void testMethod09() throws Exception {
            when(videoSourceProbe.probe(URL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.start(URL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("動画の情報を取得できませんでした。URLを確認してください: " + URL);

            verify(processLauncher, never()).launch(anyList(), any(Path.class));
            assertThat(service.list()).isEmpty();
        }

        @Test
        @DisplayName("異常系：yt-dlp を起動できなければ IllegalStateException で、仕事のフォルダーを消して枠も残さない")
        void testMethod10() throws Exception {
            givenVideo("not_live");
            when(processLauncher.launch(anyList(), any(Path.class))).thenThrow(new IOException("yt-dlp が見つかりません"));

            assertThatThrownBy(() -> service.start(URL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("yt-dlp を起動できませんでした");

            assertThat(root).isEmptyDirectory();
            assertThat(service.list()).isEmpty();
        }

        @Test
        @DisplayName("異常系：URL が空なら IllegalArgumentException で、ログイン中の利用者も調べない")
        void testMethod11() throws Exception {
            assertThatThrownBy(() -> service.start("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("動画のURLを入力してください");

            verify(currentAppUser, never()).require();
            verify(videoSourceProbe, never()).probe(anyString());
        }
    }

    @Nested
    @DisplayName("status()")
    class Status {

        @Test
        @DisplayName("正常系：本人の仕事の状態を返す")
        void testMethod01() throws Exception {
            givenVideo("not_live");
            givenLaunchBlocks();
            String jobId = service.start(URL).jobId();

            DeviceDownloadResponse response = service.status(jobId);

            assertThat(response.jobId()).isEqualTo(jobId);
            assertThat(response.status()).isEqualTo(DeviceDownloadResponse.Status.RUNNING);
            assertThat(response.title()).isEqualTo("Big Buck Bunny");
            assertThat(response.fileUrl()).isNull();
        }

        @Test
        @DisplayName("異常系：他人の仕事の ID では DeviceDownloadNotFoundException")
        void testMethod02() throws Exception {
            givenVideo("not_live");
            givenLaunchBlocks();
            String jobId = service.start(URL).jobId();
            when(currentAppUser.require()).thenReturn(bob);

            assertThatThrownBy(() -> service.status(jobId)).isInstanceOf(DeviceDownloadNotFoundException.class);
        }

        @Test
        @DisplayName("異常系：無い仕事の ID では DeviceDownloadNotFoundException")
        void testMethod03() throws Exception {
            assertThatThrownBy(() -> service.status("no-such-job"))
                    .isInstanceOf(DeviceDownloadNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("readyFile()")
    class ReadyFile {

        @Test
        @DisplayName("正常系：再生できるファイルができた仕事は READY になり、ファイルの場所と「タイトル.mp4」を返す")
        void testMethod01() throws Exception {
            lenient().when(recordingSalvager.ensurePlayable(any()))
                    .thenReturn(new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, 5L));
            givenVideo("not_live");
            givenLaunchFinishes();
            String jobId = service.start(URL).jobId();
            Path file = Files.writeString(root.resolve(jobId).resolve(VIDEO_ID + ".mp4"), "video");

            DeviceDownloadResponse finished = awaitFinished(jobId);

            assertThat(finished.status()).isEqualTo(DeviceDownloadResponse.Status.READY);
            assertThat(finished.fileUrl()).isEqualTo("/api/my/downloads/device/" + jobId + "/file");
            DeviceDownloadService.ReadyFile ready = service.readyFile(jobId);
            assertThat(ready.path()).isEqualTo(file.toAbsolutePath());
            assertThat(ready.downloadName()).isEqualTo("Big Buck Bunny.mp4");
        }

        @Test
        @DisplayName("正常系：途中まで（SALVAGED）の仕事は PARTIAL になり、ファイルを受け取れる")
        void testMethod02() throws Exception {
            lenient().when(recordingSalvager.ensurePlayable(any()))
                    .thenReturn(new SalvageOutcome(SalvageStatus.SALVAGED, 5L));
            givenVideo("not_live");
            givenLaunchFinishes();
            String jobId = service.start(URL).jobId();
            Path file = Files.writeString(root.resolve(jobId).resolve(VIDEO_ID + ".mp4"), "video");

            DeviceDownloadResponse finished = awaitFinished(jobId);

            assertThat(finished.status()).isEqualTo(DeviceDownloadResponse.Status.PARTIAL);
            assertThat(finished.fileUrl()).isEqualTo("/api/my/downloads/device/" + jobId + "/file");
            assertThat(service.readyFile(jobId).path()).isEqualTo(file.toAbsolutePath());
        }

        @Test
        @DisplayName("異常系：再生できるファイルを用意できなかった仕事は FAILED になり、ファイルがあっても DeviceDownloadNotFoundException")
        void testMethod03() throws Exception {
            givenVideo("not_live");
            givenLaunchFinishes();
            String jobId = service.start(URL).jobId();
            Files.writeString(root.resolve(jobId).resolve(VIDEO_ID + ".mp4"), "video");

            DeviceDownloadResponse finished = awaitFinished(jobId);

            assertThat(finished.status()).isEqualTo(DeviceDownloadResponse.Status.FAILED);
            assertThat(finished.fileUrl()).isNull();
            assertThatThrownBy(() -> service.readyFile(jobId)).isInstanceOf(DeviceDownloadNotFoundException.class);
        }

        @Test
        @DisplayName("異常系：取得中の仕事は DeviceDownloadNotFoundException")
        void testMethod04() throws Exception {
            givenVideo("not_live");
            givenLaunchBlocks();
            String jobId = service.start(URL).jobId();

            assertThatThrownBy(() -> service.readyFile(jobId)).isInstanceOf(DeviceDownloadNotFoundException.class);
        }

        @Test
        @DisplayName("異常系：READY でも他人の仕事は DeviceDownloadNotFoundException")
        void testMethod05() throws Exception {
            lenient().when(recordingSalvager.ensurePlayable(any()))
                    .thenReturn(new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, 5L));
            givenVideo("not_live");
            givenLaunchFinishes();
            String jobId = service.start(URL).jobId();
            Files.writeString(root.resolve(jobId).resolve(VIDEO_ID + ".mp4"), "video");
            assertThat(awaitFinished(jobId).status()).isEqualTo(DeviceDownloadResponse.Status.READY);
            when(currentAppUser.require()).thenReturn(bob);

            assertThatThrownBy(() -> service.readyFile(jobId)).isInstanceOf(DeviceDownloadNotFoundException.class);
        }

        @Test
        @DisplayName("異常系：READY でもファイルが無ければ DeviceDownloadNotFoundException")
        void testMethod06() throws Exception {
            lenient().when(recordingSalvager.ensurePlayable(any()))
                    .thenReturn(new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, 5L));
            givenVideo("not_live");
            givenLaunchFinishes();
            String jobId = service.start(URL).jobId();

            assertThat(awaitFinished(jobId).status()).isEqualTo(DeviceDownloadResponse.Status.READY);

            assertThatThrownBy(() -> service.readyFile(jobId)).isInstanceOf(DeviceDownloadNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("list()")
    class ListJobs {

        @Test
        @DisplayName("正常系：自分の仕事だけを新しい順に返す")
        void testMethod01() throws Exception {
            givenVideo("not_live");
            givenLaunchFinishes();
            String first = service.start(URL).jobId();
            awaitFinished(first);
            // 2 つの仕事の作成時刻（Instant.now()）が同じだと並び順が決まらないので、間を空けて必ず差を付ける
            Thread.sleep(50);
            String second = service.start(URL).jobId();
            when(currentAppUser.require()).thenReturn(bob);
            service.start(URL);
            when(currentAppUser.require()).thenReturn(alice);

            List<DeviceDownloadResponse> jobs = service.list();

            assertThat(jobs).extracting(DeviceDownloadResponse::jobId).containsExactly(second, first);
        }

        @Test
        @DisplayName("正常系：仕事が無ければ空のリストを返す")
        void testMethod02() throws Exception {
            assertThat(service.list()).isEmpty();
        }
    }

    @Nested
    @DisplayName("purgeExpired()")
    class PurgeExpired {

        @Test
        @DisplayName("正常系：期限を過ぎた仕事は、取得中の yt-dlp を止めてからフォルダーを消し、以後は状態を引けない")
        void testMethod01() throws Exception {
            givenVideo("not_live");
            Process process = givenLaunchBlocks();
            ProcessHandle handle = stoppableHandle();
            when(process.isAlive()).thenReturn(true);
            when(process.toHandle()).thenReturn(handle);
            String jobId = service.start(URL).jobId();
            ReflectionTestUtils.setField(service, "retention", Duration.ofMinutes(-1));

            service.purgeExpired();

            verify(handle).destroy();
            assertThat(root.resolve(jobId)).doesNotExist();
            assertThatThrownBy(() -> service.status(jobId)).isInstanceOf(DeviceDownloadNotFoundException.class);
            // メモリにある仕事は自分の Process で止めるので、プロセスを探さない
            verify(processLauncher, never()).findYtDlpProcessesWithCommandLineContaining(anyString());
        }

        @Test
        @DisplayName("正常系：期限前の仕事は、フォルダーも状態も残す")
        void testMethod02() throws Exception {
            givenVideo("not_live");
            givenLaunchBlocks();
            String jobId = service.start(URL).jobId();

            service.purgeExpired();

            assertThat(root.resolve(jobId)).isDirectory();
            assertThat(service.status(jobId).status()).isEqualTo(DeviceDownloadResponse.Status.RUNNING);
            verify(processLauncher, never()).findYtDlpProcessesWithCommandLineContaining(anyString());
        }

        @Test
        @DisplayName("正常系：メモリに無い期限切れのフォルダーは、そのパスを含む yt-dlp を止めてから消す")
        void testMethod03() throws Exception {
            Path orphan = Files.createDirectories(root.resolve("job-before-restart"));
            Files.writeString(orphan.resolve(VIDEO_ID + ".mp4"), "video");
            Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(Duration.ofHours(48))));
            ProcessHandle handle = stoppableHandle();
            when(processLauncher.findYtDlpProcessesWithCommandLineContaining(orphan.toAbsolutePath().toString()))
                    .thenReturn(List.of(handle));

            service.purgeExpired();

            verify(handle).destroy();
            assertThat(orphan).doesNotExist();
        }

        @Test
        @DisplayName("正常系：メモリに無い期限前のフォルダーは消さず、プロセスも探さない")
        void testMethod04() throws Exception {
            Path orphan = Files.createDirectories(root.resolve("job-before-restart"));
            Path file = Files.writeString(orphan.resolve(VIDEO_ID + ".mp4"), "video");

            service.purgeExpired();

            assertThat(orphan).isDirectory();
            assertThat(file).exists();
            verify(processLauncher, never()).findYtDlpProcessesWithCommandLineContaining(anyString());
        }

        @Test
        @DisplayName("正常系：メモリにある仕事のフォルダーは、更新日時が古くても孤立扱いしない")
        void testMethod05() throws Exception {
            givenVideo("not_live");
            givenLaunchBlocks();
            String jobId = service.start(URL).jobId();
            Files.setLastModifiedTime(root.resolve(jobId), FileTime.from(Instant.now().minus(Duration.ofHours(48))));

            service.purgeExpired();

            // 取得に 24 時間近くかかる動画でも、フォルダーの日時で止めない
            assertThat(root.resolve(jobId)).isDirectory();
            assertThat(service.status(jobId).status()).isEqualTo(DeviceDownloadResponse.Status.RUNNING);
            verify(processLauncher, never()).findYtDlpProcessesWithCommandLineContaining(anyString());
        }

        @Test
        @DisplayName("正常系：置き場所のフォルダーが無ければ何もしない")
        void testMethod06() throws Exception {
            assertThatCode(() -> service.purgeExpired()).doesNotThrowAnyException();

            assertThat(root).doesNotExist();
            verify(processLauncher, never()).findYtDlpProcessesWithCommandLineContaining(anyString());
        }
    }

    @Nested
    @DisplayName("downloadName()")
    class DownloadName {

        @Test
        @DisplayName("正常系：ファイル名に使えない文字と制御文字を _ に置き換え、前後の空白を除く")
        void testMethod01() throws Exception {
            assertThat(DeviceDownloadService.downloadName("a\\b/c:d*e?f\"g<h>i|j\tk", VIDEO_ID))
                    .isEqualTo("a_b_c_d_e_f_g_h_i_j_k.mp4");
            assertThat(DeviceDownloadService.downloadName("  Big Buck Bunny  ", VIDEO_ID))
                    .isEqualTo("Big Buck Bunny.mp4");
        }

        @Test
        @DisplayName("正常系：100 文字を超えるタイトルは符号位置で 100 文字に切り、絵文字を割らない")
        void testMethod02() throws Exception {
            String emoji = "😀";

            assertThat(DeviceDownloadService.downloadName(emoji.repeat(101), VIDEO_ID))
                    .isEqualTo(emoji.repeat(100) + ".mp4");
        }

        @Test
        @DisplayName("正常系：タイトルが null・空白だけなら動画 ID を名前にする")
        void testMethod03() throws Exception {
            assertThat(DeviceDownloadService.downloadName(null, VIDEO_ID)).isEqualTo("aqz-KE-bpKQ.mp4");
            assertThat(DeviceDownloadService.downloadName("   ", VIDEO_ID)).isEqualTo("aqz-KE-bpKQ.mp4");
        }
    }
}
