package com.example.monitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoMetadataExtractor")
// ArgumentCaptor.forClass(List.class) は総称型の情報を渡せず未検査キャストになる（StreamRecorderTest と同じ）
@SuppressWarnings("unchecked")
class VideoMetadataExtractorTest {

    @Mock
    private ProcessLauncher processLauncher;

    /**
     * 外部コマンドの起動そのものは {@link ExternalCommandRunner} に移したが、
     * このテストが差し替えたいのは「起動されるプロセス」なので、共通処理は本物を使い
     * {@link ProcessLauncher} だけをモックにする。
     */
    private VideoMetadataExtractor videoMetadataExtractor;

    @BeforeEach
    void setUpExtractor() {
        videoMetadataExtractor = new VideoMetadataExtractor(new ExternalCommandRunner(processLauncher));
    }

    /**
     * 指定した標準出力と終了コードを返す、外部コマンドの代わりのプロセスを作る。
     *
     * @param standardOutput 標準出力として読ませたい内容
     * @param exitCode       終了コード
     * @return 組み立てたモックプロセス
     */
    private Process mockProcess(String standardOutput, int exitCode) throws InterruptedException {
        Process process = mock(Process.class);
        when(process.getInputStream())
                .thenReturn(new ByteArrayInputStream(standardOutput.getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(anyLong(), any())).thenReturn(true);
        when(process.exitValue()).thenReturn(exitCode);
        return process;
    }

    @Nested
    @DisplayName("extractDurationSeconds()")
    class ExtractDurationSeconds {

        @Test
        @DisplayName("正常系：ffprobeが出力した秒数を整数秒として返す")
        void testMethod01(@TempDir Path tempDir) throws Exception {
            Path videoFile = Files.createFile(tempDir.resolve("video001.mp4"));
            Process process = mockProcess("5432.123456\n", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            Optional<Integer> result = videoMetadataExtractor.extractDurationSeconds(videoFile);

            assertThat(result).contains(5432);
        }

        @Test
        @DisplayName("正常系：ffprobeに対象ファイルのパスを渡している")
        void testMethod02(@TempDir Path tempDir) throws Exception {
            Path videoFile = Files.createFile(tempDir.resolve("video001.mp4"));
            Process process = mockProcess("10.0\n", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            videoMetadataExtractor.extractDurationSeconds(videoFile);

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processLauncher).launch(captor.capture());
            assertThat(captor.getValue()).first().isEqualTo("ffprobe");
            assertThat(captor.getValue()).last().isEqualTo(videoFile.toString());
        }

        @Test
        @DisplayName("異常系：ファイルが存在しない場合はコマンドを起動せず空を返す")
        void testMethod03(@TempDir Path tempDir) throws Exception {
            Optional<Integer> result =
                    videoMetadataExtractor.extractDurationSeconds(tempDir.resolve("missing.mp4"));

            assertThat(result).isEmpty();
            verify(processLauncher, never()).launch(any());
        }

        @Test
        @DisplayName("異常系：ffprobeがインストールされていない場合は空を返す")
        void testMethod04(@TempDir Path tempDir) throws Exception {
            Path videoFile = Files.createFile(tempDir.resolve("video001.mp4"));
            when(processLauncher.launch(any())).thenThrow(new IOException("not found"));

            assertThat(videoMetadataExtractor.extractDurationSeconds(videoFile)).isEmpty();
        }

        @Test
        @DisplayName("異常系：ffprobeが異常終了した場合は空を返す")
        void testMethod05(@TempDir Path tempDir) throws Exception {
            Path videoFile = Files.createFile(tempDir.resolve("video001.mp4"));
            Process process = mockProcess("", 1);
            when(processLauncher.launch(any())).thenReturn(process);

            assertThat(videoMetadataExtractor.extractDurationSeconds(videoFile)).isEmpty();
        }

        @Test
        @DisplayName("異常系：出力が数値として解釈できない場合は空を返す")
        void testMethod06(@TempDir Path tempDir) throws Exception {
            Path videoFile = Files.createFile(tempDir.resolve("video001.mp4"));
            Process process = mockProcess("N/A\n", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            assertThat(videoMetadataExtractor.extractDurationSeconds(videoFile)).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractThumbnail()")
    class ExtractThumbnail {

        @Test
        @DisplayName("正常系：ffmpegが画像を作った場合はそのパスを返す")
        void testMethod01(@TempDir Path tempDir) throws Exception {
            Path videoFile = Files.createFile(tempDir.resolve("video001.mp4"));
            Path thumbnail = tempDir.resolve("video001.jpg");
            // ffmpeg が画像を書き出す様子を模す
            Process process = mockProcess("", 0);
            when(processLauncher.launch(any())).thenAnswer(invocation -> {
                Files.createFile(thumbnail);
                return process;
            });

            assertThat(videoMetadataExtractor.extractThumbnail(videoFile, 100)).contains(thumbnail);
        }

        @Test
        @DisplayName("正常系：再生時間の10%の位置を切り出し位置としてffmpegへ渡す")
        void testMethod02(@TempDir Path tempDir) throws Exception {
            Path videoFile = Files.createFile(tempDir.resolve("video001.mp4"));
            Process process = mockProcess("", 0);
            when(processLauncher.launch(any())).thenAnswer(invocation -> {
                Files.createFile(tempDir.resolve("video001.jpg"));
                return process;
            });

            videoMetadataExtractor.extractThumbnail(videoFile, 1000);

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processLauncher).launch(captor.capture());
            List<String> command = captor.getValue();
            assertThat(command).first().isEqualTo("ffmpeg");
            assertThat(command.get(command.indexOf("-ss") + 1)).isEqualTo("100");
        }

        @Test
        @DisplayName("正常系：既に画像がある場合は作り直さずそのまま返す")
        void testMethod03(@TempDir Path tempDir) throws Exception {
            Path videoFile = Files.createFile(tempDir.resolve("video001.mp4"));
            Path thumbnail = Files.createFile(tempDir.resolve("video001.jpg"));

            assertThat(videoMetadataExtractor.extractThumbnail(videoFile, 100)).contains(thumbnail);
            verify(processLauncher, never()).launch(any());
        }

        @Test
        @DisplayName("異常系：ffmpegが成功しても画像が出来ていない場合は空を返す")
        void testMethod04(@TempDir Path tempDir) throws Exception {
            Path videoFile = Files.createFile(tempDir.resolve("video001.mp4"));
            Process process = mockProcess("", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            assertThat(videoMetadataExtractor.extractThumbnail(videoFile, 100)).isEmpty();
        }

        @Test
        @DisplayName("異常系：ファイルが存在しない場合はコマンドを起動せず空を返す")
        void testMethod05(@TempDir Path tempDir) throws Exception {
            assertThat(videoMetadataExtractor.extractThumbnail(tempDir.resolve("missing.mp4"), 100))
                    .isEmpty();
            verify(processLauncher, never()).launch(any());
        }
    }

    @Nested
    @DisplayName("thumbnailPathFor()")
    class ThumbnailPathFor {

        @Test
        @DisplayName("正常系：拡張子をjpgに置き換えた同じディレクトリのパスを返す")
        void testMethod01(@TempDir Path tempDir) {
            Path result = videoMetadataExtractor.thumbnailPathFor(tempDir.resolve("video001.mp4"));

            assertThat(result).isEqualTo(tempDir.resolve("video001.jpg"));
        }

        @Test
        @DisplayName("正常系：拡張子が無いファイルにはjpgを付け足す")
        void testMethod02(@TempDir Path tempDir) {
            Path result = videoMetadataExtractor.thumbnailPathFor(tempDir.resolve("video001"));

            assertThat(result).isEqualTo(tempDir.resolve("video001.jpg"));
        }
    }
}
