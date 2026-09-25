package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.dto.DiskUsageResponse.ChannelDiskUsage;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingFileService")
class RecordingFileServiceTest {

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private RecordingRepository recordingRepository;

    private RecordingFileService newService(Path recordingDirectory) {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties("", ""),
                new DiscordProperties(""),
                new RecordingProperties(recordingDirectory.toString(), 0),
                new MonitorProperties.AdminProperties("admin", ""));
        return new RecordingFileService(properties, monitoredChannelRepository, recordingRepository);
    }

    @Nested
    @DisplayName("deleteFile()")
    class DeleteFile {

        @Test
        @DisplayName("正常系：存在するファイルを削除する")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCxxxxxxxx"));
            Path file = tempDir.resolve("UCxxxxxxxx/video001.mp4");
            Files.writeString(file, "dummy");
            Recording recording = Recording.builder()
                    .videoId("video001").filePath("UCxxxxxxxx/video001.mp4").build();

            newService(tempDir).deleteFile(recording);

            assertThat(Files.exists(file)).isFalse();
        }

        @Test
        @DisplayName("正常系：ファイルが存在しなくても例外を発生させない")
        void testMethod02(@TempDir Path tempDir) {
            Recording recording = Recording.builder()
                    .videoId("missing").filePath("UCxxxxxxxx/missing.mp4").build();

            org.assertj.core.api.Assertions.assertThatCode(() -> newService(tempDir).deleteFile(recording))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常系：マージ前の断片ファイルもまとめて削除する")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            // 録画が失敗してマージ前に中断されると、完成ファイルは無く断片だけが残る（実際に発生した）
            Files.createDirectories(tempDir.resolve("UCxxxxxxxx"));
            Files.writeString(tempDir.resolve("UCxxxxxxxx/video001.f137.mp4"), "video");
            Files.writeString(tempDir.resolve("UCxxxxxxxx/video001.f140.mp4.ytdl"), "resume");
            Recording recording = Recording.builder()
                    .videoId("video001").filePath("UCxxxxxxxx/video001.mp4").build();

            newService(tempDir).deleteFile(recording);

            try (java.util.stream.Stream<Path> remaining = Files.list(tempDir.resolve("UCxxxxxxxx"))) {
                assertThat(remaining).isEmpty();
            }
        }

        @Test
        @DisplayName("正常系：同じディレクトリの別動画のファイルは削除しない")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCxxxxxxxx"));
            Files.writeString(tempDir.resolve("UCxxxxxxxx/video001.mp4"), "target");
            Path other = tempDir.resolve("UCxxxxxxxx/video002.mp4");
            Files.writeString(other, "keep");
            Recording recording = Recording.builder()
                    .videoId("video001").filePath("UCxxxxxxxx/video001.mp4").build();

            newService(tempDir).deleteFile(recording);

            assertThat(Files.exists(other)).isTrue();
        }
    }

    @Nested
    @DisplayName("sizeIfExists()")
    class SizeIfExists {

        @Test
        @DisplayName("正常系：ファイルが存在する場合はサイズを返す")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCxxxxxxxx"));
            Files.write(tempDir.resolve("UCxxxxxxxx/video001.mp4"), new byte[12345]);
            Recording recording = Recording.builder().filePath("UCxxxxxxxx/video001.mp4").build();

            java.util.Optional<Long> result = newService(tempDir).sizeIfExists(recording);

            assertThat(result).contains(12345L);
        }

        @Test
        @DisplayName("正常系：ファイルが存在しない場合は空を返す")
        void testMethod02(@TempDir Path tempDir) {
            Recording recording = Recording.builder().filePath("UCxxxxxxxx/missing.mp4").build();

            java.util.Optional<Long> result = newService(tempDir).sizeIfExists(recording);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系：パスがディレクトリの場合は空を返す")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCxxxxxxxx/video001.mp4"));
            Recording recording = Recording.builder().filePath("UCxxxxxxxx/video001.mp4").build();

            java.util.Optional<Long> result = newService(tempDir).sizeIfExists(recording);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("totalFileSizeFor()")
    class TotalFileSizeFor {

        @Test
        @DisplayName("正常系：完成ファイルと断片をまとめて合計する")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            // 断片が増えたことを検知できないと、後から揃った録画を救済できなくなる
            Files.createDirectories(tempDir.resolve("UCxxxxxxxx"));
            Files.write(tempDir.resolve("UCxxxxxxxx/video001.f137.mp4"), new byte[300]);
            Files.write(tempDir.resolve("UCxxxxxxxx/video001.f140.m4a"), new byte[100]);
            Recording recording = Recording.builder().filePath("UCxxxxxxxx/video001.mp4").build();

            assertThat(newService(tempDir).totalFileSizeFor(recording)).isEqualTo(400L);
        }

        @Test
        @DisplayName("正常系：同じディレクトリの別動画のファイルは数えない")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCxxxxxxxx"));
            Files.write(tempDir.resolve("UCxxxxxxxx/video001.mp4"), new byte[500]);
            Files.write(tempDir.resolve("UCxxxxxxxx/video002.mp4"), new byte[999]);
            Recording recording = Recording.builder().filePath("UCxxxxxxxx/video001.mp4").build();

            assertThat(newService(tempDir).totalFileSizeFor(recording)).isEqualTo(500L);
        }

        @Test
        @DisplayName("正常系：ファイルが1つも無ければ0を返す")
        void testMethod03(@TempDir Path tempDir) {
            Recording recording = Recording.builder().filePath("UCxxxxxxxx/video001.mp4").build();

            assertThat(newService(tempDir).totalFileSizeFor(recording)).isZero();
        }
    }

    @Nested
    @DisplayName("resolveExistingFile()")
    class ResolveExistingFile {

        @Test
        @DisplayName("正常系：ファイルが存在する場合は絶対パスを返す")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCxxxxxxxx"));
            Files.createFile(tempDir.resolve("UCxxxxxxxx/video001.mp4"));
            Recording recording = Recording.builder().filePath("UCxxxxxxxx/video001.mp4").build();

            assertThat(newService(tempDir).resolveExistingFile(recording))
                    .contains(tempDir.resolve("UCxxxxxxxx/video001.mp4"));
        }

        @Test
        @DisplayName("正常系：ファイルが存在しない場合は空を返す")
        void testMethod02(@TempDir Path tempDir) {
            Recording recording = Recording.builder().filePath("UCxxxxxxxx/missing.mp4").build();

            assertThat(newService(tempDir).resolveExistingFile(recording)).isEmpty();
        }
    }

    @Nested
    @DisplayName("toRelativePath()")
    class ToRelativePath {

        @Test
        @DisplayName("正常系：録画ディレクトリからの相対パスに変換する")
        void testMethod01(@TempDir Path tempDir) {
            String result = newService(tempDir).toRelativePath(tempDir.resolve("UCxxxxxxxx/video001.jpg"));

            assertThat(result).isEqualTo("UCxxxxxxxx/video001.jpg");
        }
    }

    @Nested
    @DisplayName("calculateUsage()")
    class CalculateUsage {

        @Test
        @DisplayName("正常系：録画ディレクトリが存在しない場合は合計0を返す")
        void testMethod01(@TempDir Path tempDir) {
            Path nonExistent = tempDir.resolve("does-not-exist");

            DiskUsageResponse result = newService(nonExistent).calculateUsage();

            assertThat(result.totalBytes()).isZero();
            assertThat(result.byChannel()).isEmpty();
        }

        @Test
        @DisplayName("正常系：チャンネル別に使用量を集計し、多い順に並べる")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCsmall"));
            Files.write(tempDir.resolve("UCsmall/video001.mp4"), new byte[10]);
            Files.createDirectories(tempDir.resolve("UClarge"));
            Files.write(tempDir.resolve("UClarge/video002.mp4"), new byte[100]);

            MonitoredChannel small = new MonitoredChannel("UCsmall", "小さいチャンネル");
            MonitoredChannel large = new MonitoredChannel("UClarge", "大きいチャンネル");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(small, large));

            DiskUsageResponse result = newService(tempDir).calculateUsage();

            assertThat(result.totalBytes()).isEqualTo(110L);
            assertThat(result.byChannel()).extracting(ChannelDiskUsage::channelName, ChannelDiskUsage::bytes)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("大きいチャンネル", 100L),
                            org.assertj.core.groups.Tuple.tuple("小さいチャンネル", 10L));
        }

        @Test
        @DisplayName("正常系：DBに存在しないチャンネルのディレクトリは削除済みチャンネルとして扱う")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCorphan"));
            Files.write(tempDir.resolve("UCorphan/video001.mp4"), new byte[10]);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());

            DiskUsageResponse result = newService(tempDir).calculateUsage();

            assertThat(result.byChannel()).hasSize(1);
            assertThat(result.byChannel().get(0).channelName()).isEqualTo("(削除済みチャンネル)");
            assertThat(result.byChannel().get(0).youtubeChannelId()).isEqualTo("UCorphan");
        }

        @Test
        @DisplayName("正常系：サブディレクトリ内のファイルも合計に含める")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCxxxxxxxx/nested"));
            Files.write(tempDir.resolve("UCxxxxxxxx/video001.mp4"), new byte[10]);
            Files.write(tempDir.resolve("UCxxxxxxxx/nested/video002.mp4"), new byte[20]);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());

            DiskUsageResponse result = newService(tempDir).calculateUsage();

            assertThat(result.totalBytes()).isEqualTo(30L);
        }

        @Test
        @DisplayName("正常系：ダウンロードした録画が残っているディレクトリは未登録チャンネルとして扱う")
        void testMethod06(@TempDir Path tempDir) throws IOException {
            // 一括削除の対象になるのは「削除済み」だけ。同じ表示にすると
            // 「削除済みと出ているのに消えない」という説明のつかない状態に見える
            Files.createDirectories(tempDir.resolve("downloads"));
            Files.write(tempDir.resolve("downloads/aqz-KE-bpKQ.mp4"), new byte[10]);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());
            when(recordingRepository.findByChannelIsNull()).thenReturn(List.of(
                    Recording.builder().videoId("aqz-KE-bpKQ")
                            .filePath("downloads/aqz-KE-bpKQ.mp4").build()));

            DiskUsageResponse result = newService(tempDir).calculateUsage();

            assertThat(result.byChannel()).hasSize(1);
            assertThat(result.byChannel().get(0).channelName()).isEqualTo("(未登録チャンネル)");
        }

        @Test
        @DisplayName("正常系：登録有無がregisteredフラグで区別できる")
        void testMethod05(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("UCregistered"));
            Files.write(tempDir.resolve("UCregistered/video001.mp4"), new byte[100]);
            Files.createDirectories(tempDir.resolve("UCorphan"));
            Files.write(tempDir.resolve("UCorphan/video002.mp4"), new byte[10]);
            when(monitoredChannelRepository.findAll())
                    .thenReturn(List.of(new MonitoredChannel("UCregistered", "登録中チャンネル")));

            DiskUsageResponse result = newService(tempDir).calculateUsage();

            assertThat(result.byChannel())
                    .extracting(ChannelDiskUsage::youtubeChannelId, ChannelDiskUsage::registered)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("UCregistered", true),
                            org.assertj.core.groups.Tuple.tuple("UCorphan", false));
        }
    }
}
