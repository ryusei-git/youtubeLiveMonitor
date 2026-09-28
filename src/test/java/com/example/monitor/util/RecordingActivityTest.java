package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RecordingActivity")
class RecordingActivityTest {

    private static final Instant T1 = Instant.parse("2026-01-01T00:01:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T00:02:00Z");
    private static final Instant T3 = Instant.parse("2026-01-01T00:03:00Z");
    private static final Instant T5 = Instant.parse("2026-01-01T00:05:00Z");
    private static final Instant T9 = Instant.parse("2026-01-01T00:09:00Z");

    private static Path touch(Path dir, String name, Instant modified) throws IOException {
        Path file = Files.createFile(dir.resolve(name));
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Nested
    @DisplayName("lastModified()")
    class LastModified {

        @Test
        @DisplayName("正常系：同じ動画 ID の断片・作業ファイル・完成ファイルのうち、最も新しい更新時刻を返す")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            touch(tempDir, "ratest_abc.f137.mp4.part-Frag3", T3);
            touch(tempDir, "ratest_abc.mp4.ytdl", T1);
            touch(tempDir, "ratest_abc.temp.mp4", T2);

            Instant result = RecordingActivity.lastModified(tempDir, "ratest_abc");

            assertThat(result).isEqualTo(T3);
        }

        @Test
        @DisplayName("正常系：前方一致するだけの別の動画 ID のファイルは見ない")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            touch(tempDir, "ratest_abc.mp4", T1);
            touch(tempDir, "ratest_abcd.mp4", T9);
            touch(tempDir, "ratest_abcd.f137.mp4.part-Frag1", T9);

            Instant result = RecordingActivity.lastModified(tempDir, "ratest_abc");

            assertThat(result).isEqualTo(T1);
        }

        @Test
        @DisplayName("正常系：動画 ID で始まる名前のフォルダーは見ない")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            touch(tempDir, "ratest_abc.mp4", T1);
            Path directory = Files.createDirectory(tempDir.resolve("ratest_abc.parts"));
            Files.setLastModifiedTime(directory, FileTime.from(T9));

            Instant result = RecordingActivity.lastModified(tempDir, "ratest_abc");

            assertThat(result).isEqualTo(T1);
        }

        @Test
        @DisplayName("正常系：対象のファイルもログも無ければ Instant.EPOCH を返す")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            touch(tempDir, "other.mp4", T9);

            Instant result = RecordingActivity.lastModified(tempDir, "ratest_abc");

            assertThat(result).isEqualTo(Instant.EPOCH);
        }

        @Test
        @DisplayName("正常系：yt-dlp のログの方が新しければ、ログの更新時刻を返す")
        void testMethod05(@TempDir Path tempDir) throws IOException {
            String videoId = "RecordingActivityTest-" + UUID.randomUUID();
            touch(tempDir, videoId + ".mp4", T1);
            // ログの場所は YtDlpLogFile.of() が作業ディレクトリからの相対パスで決めていて差し替えられないため、作業ディレクトリの logs/yt-dlp/ に書く
            Path log = YtDlpLogFile.of(videoId);
            Files.createDirectories(log.getParent());
            Files.createFile(log);
            Files.setLastModifiedTime(log, FileTime.from(T5));

            try {
                assertThat(RecordingActivity.lastModified(tempDir, videoId)).isEqualTo(T5);
            } finally {
                Files.deleteIfExists(log);
            }
        }

        @Test
        @DisplayName("異常系：録画フォルダーが無ければ IOException を投げる")
        void testMethod06(@TempDir Path tempDir) {
            Path missing = tempDir.resolve("missing");

            assertThatThrownBy(() -> RecordingActivity.lastModified(missing, "ratest_abc"))
                    .isInstanceOf(IOException.class);
        }
    }
}
