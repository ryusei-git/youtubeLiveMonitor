package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.dto.OrphanedCleanupResponse;
import com.example.monitor.repository.RecordingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrphanedPreviewService")
class OrphanedPreviewServiceTest {

    @Mock private RecordingRepository repository;
    @Mock private ProcessLauncher processLauncher;
    /** 予約は本物を使う（予約中の除外と、削除の後に予約を外すことを確かめるため）。 */
    private final ActiveVideoJobs activeVideoJobs = new ActiveVideoJobs();

    /** 録画の保存先（{@code <一時フォルダー>/recordings}）。テストの初めには無い。 */
    private Path base;
    private OrphanedPreviewService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        base = tempDir.resolve("recordings");
        service = newService(activeVideoJobs);
    }

    private OrphanedPreviewService newService(ActiveVideoJobs jobs) {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties("", ""),
                new DiscordProperties(""),
                new RecordingProperties(base.toString(), 0),
                new MonitorProperties.AdminProperties("admin", ""));
        return new OrphanedPreviewService(properties, repository, processLauncher, jobs);
    }

    /** 保存先の下の {@code relativePath} に {@code content} を書いたファイルを作る（フォルダーも作る）。 */
    private Path file(String relativePath, String content) throws IOException {
        Path file = base.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Nested
    @DisplayName("preview()")
    class Preview {

        @Test
        @DisplayName("正常系：録画履歴に無い動画のファイルを候補にし、相対パス・動画 ID・大きさと合計を返して、ファイルは消さない")
        void testMethod01() throws Exception {
            Path first = file("UCorphan/orphan00001.mp4", "12345");
            Path second = file("UCorphan/orphan00002.mp4", "123");

            OrphanedPreviewService.Preview preview = service.preview();

            // 区切りは Windows でも /
            assertThat(preview.files()).extracting(OrphanedPreviewService.Candidate::path)
                    .containsExactly("UCorphan/orphan00001.mp4", "UCorphan/orphan00002.mp4");
            assertThat(preview.files()).extracting(OrphanedPreviewService.Candidate::videoId)
                    .containsExactly("orphan00001", "orphan00002");
            assertThat(preview.files()).extracting(OrphanedPreviewService.Candidate::bytes)
                    .containsExactly(5L, 3L);
            assertThat(preview.totalBytes()).isEqualTo(8);
            assertThat(preview.skipped()).isEmpty();
            assertThat(preview.token()).isNotEmpty();
            assertThat(first).exists();
            assertThat(second).exists();
        }

        @Test
        @DisplayName("正常系：録画履歴にある動画のファイルは、断片も含めて候補にしない")
        void testMethod02() throws Exception {
            when(repository.findAllVideoIds()).thenReturn(List.of("known000001"));
            file("UCchan/known000001.mp4", "1");
            file("UCchan/known000001.f299.mp4", "2");
            file("UCchan/orphan00001.mp4", "3");

            OrphanedPreviewService.Preview preview = service.preview();

            assertThat(preview.files()).extracting(OrphanedPreviewService.Candidate::path)
                    .containsExactly("UCchan/orphan00001.mp4");
        }

        @Test
        @DisplayName("正常系：保存先の直下のファイルは、チャンネルのフォルダーの外なので候補にしない")
        void testMethod03() throws Exception {
            file("stray.mp4", "1");
            file("UCorphan/orphan00001.mp4", "1");

            OrphanedPreviewService.Preview preview = service.preview();

            assertThat(preview.files()).extracting(OrphanedPreviewService.Candidate::path)
                    .containsExactly("UCorphan/orphan00001.mp4");
        }

        @Test
        @DisplayName("正常系：録画・ダウンロードの予約中の動画は候補にせず、チャンネルを「取得中」として返す")
        void testMethod04() throws Exception {
            activeVideoJobs.reserve("orphan00001");
            file("UCorphan/orphan00001.mp4", "1");

            OrphanedPreviewService.Preview preview = service.preview();

            assertThat(preview.files()).isEmpty();
            assertThat(preview.skipped()).containsExactly("UCorphan（取得中）");
        }

        @Test
        @DisplayName("正常系：動画 ID を含むプロセスが動いているファイルは候補にしない")
        void testMethod05() throws Exception {
            when(processLauncher.isRunningWithCommandLineContaining(anyString()))
                    .thenAnswer(invocation -> "orphan00001".equals(invocation.getArgument(0)));
            file("UCorphan/orphan00001.mp4", "1");
            file("UCother/orphan00002.mp4", "1");

            OrphanedPreviewService.Preview preview = service.preview();

            assertThat(preview.files()).extracting(OrphanedPreviewService.Candidate::path)
                    .containsExactly("UCother/orphan00002.mp4");
            assertThat(preview.skipped()).containsExactly("UCorphan（取得中）");
        }

        @Test
        @DisplayName("正常系：チャンネル ID を含むプロセスが動いているチャンネルのファイルは候補にしない")
        void testMethod06() throws Exception {
            when(processLauncher.isRunningWithCommandLineContaining(anyString()))
                    .thenAnswer(invocation -> "UCbusy".equals(invocation.getArgument(0)));
            file("UCbusy/orphan00001.mp4", "1");
            file("UCfree/orphan00002.mp4", "1");

            OrphanedPreviewService.Preview preview = service.preview();

            assertThat(preview.files()).extracting(OrphanedPreviewService.Candidate::path)
                    .containsExactly("UCfree/orphan00002.mp4");
            assertThat(preview.skipped()).containsExactly("UCbusy（取得中）");
        }

        @Test
        @DisplayName("正常系：保存先のフォルダーが無ければ、候補は空で例外にならない")
        void testMethod07() throws Exception {
            OrphanedPreviewService.Preview preview = service.preview();

            assertThat(preview.files()).isEmpty();
            assertThat(preview.skipped()).isEmpty();
            assertThat(preview.totalBytes()).isZero();
        }

        @Test
        @DisplayName("正常系：ファイルが変わらなければ同じトークン、大きさが変われば別のトークンを返す")
        void testMethod08() throws Exception {
            Path file = file("UCorphan/orphan00001.mp4", "12345");

            String first = service.preview().token();
            String second = service.preview().token();
            Files.writeString(file, "678", StandardOpenOption.APPEND);
            String third = service.preview().token();

            assertThat(second).isEqualTo(first);
            assertThat(third).isNotEqualTo(first);
        }
    }

    @Nested
    @DisplayName("deleteConfirmed()")
    class DeleteConfirmed {

        @Test
        @DisplayName("正常系：確認したトークンで確定すると候補だけを消し、チャンネル数・ファイル数・解放した容量を返して予約を外す")
        void testMethod01() throws Exception {
            when(repository.findAllVideoIds()).thenReturn(List.of("known000001"));
            Path first = file("UCa/orphan00001.mp4", "12345");
            Path second = file("UCa/orphan00002.mp4", "123");
            Path third = file("UCb/orphan00003.mp4", "1");
            Path known = file("UCa/known000001.mp4", "k");
            String token = service.preview().token();

            OrphanedCleanupResponse result = service.deleteConfirmed(token);

            assertThat(result.deletedChannels()).isEqualTo(2);
            assertThat(result.deletedFiles()).isEqualTo(3);
            assertThat(result.freedBytes()).isEqualTo(9);
            assertThat(result.skippedChannels()).isEmpty();
            assertThat(first).doesNotExist();
            assertThat(second).doesNotExist();
            assertThat(third).doesNotExist();
            assertThat(known).exists();
            assertThat(activeVideoJobs.isActive("orphan00001")).isFalse();
            assertThat(activeVideoJobs.isActive("orphan00002")).isFalse();
            assertThat(activeVideoJobs.isActive("orphan00003")).isFalse();
        }

        @Test
        @DisplayName("異常系：トークンが違えば IllegalArgumentException で、何も消さない")
        void testMethod02() throws Exception {
            Path file = file("UCa/orphan00001.mp4", "12345");

            assertThatThrownBy(() -> service.deleteConfirmed("not-a-token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("対象が変わりました。削除候補を再確認してください。");

            assertThat(file).exists();
        }

        @Test
        @DisplayName("異常系：確認の後にファイルの大きさが変わっていれば IllegalArgumentException で、何も消さない")
        void testMethod03() throws Exception {
            Path file = file("UCa/orphan00001.mp4", "12345");
            String token = service.preview().token();
            Files.writeString(file, "678", StandardOpenOption.APPEND);

            assertThatThrownBy(() -> service.deleteConfirmed(token)).isInstanceOf(IllegalArgumentException.class);

            assertThat(file).exists();
            assertThat(Files.readString(file)).isEqualTo("12345678");
        }

        @Test
        @DisplayName("異常系：確認の後に孤立ファイルが増えていれば IllegalArgumentException で、元のファイルも増えたファイルも消さない")
        void testMethod04() throws Exception {
            Path first = file("UCa/orphan00001.mp4", "12345");
            String token = service.preview().token();
            Path added = file("UCa/orphan00002.mp4", "123");

            assertThatThrownBy(() -> service.deleteConfirmed(token)).isInstanceOf(IllegalArgumentException.class);

            assertThat(first).exists();
            assertThat(added).exists();
        }

        @Test
        @DisplayName("正常系：トークンの照合の後に録画履歴に載ったファイルは消さず、状態が変わったものとして返す")
        void testMethod05() throws Exception {
            Path file = file("UCa/orphan00001.mp4", "12345");
            // findAllVideoIds は空のままなので、トークンは合う
            when(repository.existsByVideoId("orphan00001")).thenReturn(true);

            OrphanedCleanupResponse result = service.deleteConfirmed(service.preview().token());

            assertThat(result.deletedFiles()).isZero();
            assertThat(result.deletedChannels()).isZero();
            assertThat(result.skippedChannels()).containsExactly("UCa/orphan00001.mp4（状態が変化）");
            assertThat(file).exists();
            assertThat(activeVideoJobs.isActive("orphan00001")).isFalse();
        }

        @Test
        @DisplayName("正常系：予約が取れないファイルは消さず取得中として返し、他の予約を外さない")
        void testMethod06() throws Exception {
            // isActive も reserve も既定の false
            ActiveVideoJobs jobs = mock(ActiveVideoJobs.class);
            OrphanedPreviewService service = newService(jobs);
            Path file = file("UCa/orphan00001.mp4", "12345");

            OrphanedCleanupResponse result = service.deleteConfirmed(service.preview().token());

            assertThat(result.deletedFiles()).isZero();
            assertThat(result.skippedChannels()).containsExactly("UCa/orphan00001.mp4（取得中）");
            assertThat(file).exists();
            verify(jobs, never()).release(anyString());
        }

        @Test
        @DisplayName("正常系：予約を取るまでの間にファイルが書き換わったら消さず、状態が変わったものとして返して予約を外す")
        void testMethod07() throws Exception {
            Path file = file("UCa/orphan00001.mp4", "12345");
            ActiveVideoJobs jobs = mock(ActiveVideoJobs.class);
            when(jobs.reserve("orphan00001")).thenAnswer(invocation -> {
                Files.writeString(file, "678", StandardOpenOption.APPEND);
                return true;
            });
            OrphanedPreviewService service = newService(jobs);

            OrphanedCleanupResponse result = service.deleteConfirmed(service.preview().token());

            assertThat(result.deletedFiles()).isZero();
            assertThat(result.skippedChannels()).containsExactly("UCa/orphan00001.mp4（状態が変化）");
            assertThat(file).exists();
            verify(jobs).release("orphan00001");
        }
    }
}
