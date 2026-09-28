package com.example.monitor.cli;

import com.example.monitor.entity.Recording;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.RecordingFileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code sound detect} の入力の検証と、録画・ファイルが無いときの扱いを確かめる。検出そのもの（ffmpeg で音声を読む）は、
 * この端末と CI に ffmpeg が無いので扱わない。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SoundDetectCommand")
class SoundDetectCommandTest {

    private static final String RANGE_ERROR = "--from は 0 以上、--to は --from より後を指定してください";

    @Mock
    private RecordingRepository recordingRepository;

    @Mock
    private RecordingFileService recordingFileService;

    @InjectMocks
    private SoundDetectCommand command;

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void redirectStreams() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Nested
    @DisplayName("call()")
    class Call {

        @Test
        @DisplayName("異常系：--from が負なら録画を探さずに 1 を返す")
        void testMethod01() {
            ReflectionTestUtils.setField(command, "recordingId", 1L);
            ReflectionTestUtils.setField(command, "fromSeconds", -1.0);

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains(RANGE_ERROR);
            verifyNoInteractions(recordingRepository, recordingFileService);
        }

        @Test
        @DisplayName("異常系：--to が --from と同じなら 1 を返す")
        void testMethod02() {
            ReflectionTestUtils.setField(command, "recordingId", 1L);
            ReflectionTestUtils.setField(command, "fromSeconds", 10.0);
            ReflectionTestUtils.setField(command, "toSeconds", 10.0);

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains(RANGE_ERROR);
            verifyNoInteractions(recordingRepository, recordingFileService);
        }

        @Test
        @DisplayName("異常系：--from を省略して --to に 0 を指定すると 1 を返す")
        void testMethod03() {
            ReflectionTestUtils.setField(command, "recordingId", 1L);
            ReflectionTestUtils.setField(command, "toSeconds", 0.0);

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            // 省略した --from は 0 として比べる
            assertThat(errContent.toString()).contains(RANGE_ERROR);
            verifyNoInteractions(recordingRepository, recordingFileService);
        }

        @Test
        @DisplayName("正常系：--from 0 とそれより後の --to は範囲の検証を通り、録画を探す")
        void testMethod04() {
            ReflectionTestUtils.setField(command, "recordingId", 1L);
            ReflectionTestUtils.setField(command, "fromSeconds", 0.0);
            ReflectionTestUtils.setField(command, "toSeconds", 0.5);
            when(recordingRepository.findById(1L)).thenReturn(Optional.empty());

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString())
                    .doesNotContain(RANGE_ERROR)
                    .contains("録画が見つかりません: ID 1");
        }

        @Test
        @DisplayName("異常系：録画が見つからなければ、ファイルを探さずに 1 を返す")
        void testMethod05() {
            ReflectionTestUtils.setField(command, "recordingId", 99L);
            when(recordingRepository.findById(99L)).thenReturn(Optional.empty());

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("録画が見つかりません: ID 99");
            verifyNoInteractions(recordingFileService);
        }

        @Test
        @DisplayName("異常系：録画ファイルが無ければ 1 を返す")
        void testMethod06(@TempDir Path tempDir) {
            Recording recording = new Recording();
            ReflectionTestUtils.setField(command, "recordingId", 1L);
            when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
            Path missing = tempDir.resolve("missing.mp4");
            when(recordingFileService.resolveFilePath(recording)).thenReturn(missing);

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("録画ファイルがありません: " + missing);
        }

        @Test
        @DisplayName("異常系：録画のパスがフォルダなら、ファイルが無いものとして 1 を返す")
        void testMethod07(@TempDir Path tempDir) {
            Recording recording = new Recording();
            ReflectionTestUtils.setField(command, "recordingId", 1L);
            when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
            when(recordingFileService.resolveFilePath(recording)).thenReturn(tempDir);

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("録画ファイルがありません: " + tempDir);
        }
    }
}
