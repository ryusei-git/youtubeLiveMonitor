package com.example.monitor.service;

import com.example.monitor.dto.VideoSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoSourceProbe")
// ArgumentCaptor.forClass(List.class) は総称型の情報を渡せず未検査キャストになる（StreamRecorderTest と同じ）
@SuppressWarnings("unchecked")
class VideoSourceProbeTest {

    @Mock
    private ProcessLauncher processLauncher;

    /**
     * 差し替えたいのは「起動されるプロセス」なので、共通処理（{@link ExternalCommandRunner}）は
     * 本物を使い {@link ProcessLauncher} だけをモックにする（{@code VideoMetadataExtractorTest} と同じ方針）。
     */
    private VideoSourceProbe videoSourceProbe;

    @BeforeEach
    void setUpProbe() {
        videoSourceProbe = new VideoSourceProbe(new ExternalCommandRunner(processLauncher));
    }

    /**
     * 指定した標準出力と終了コードを返す、{@code yt-dlp} の代わりのプロセスを作る。
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
    @DisplayName("probe()")
    class Probe {

        @Test
        @DisplayName("正常系：yt-dlpの出力から動画の素性を取り出す")
        void testMethod01() throws Exception {
            // ヘルパーの中で when(...) を使うため、外側の when(...) の引数に直接書かない
            Process process = mockProcess(
                    "youtube|aqz-KE-bpKQ|UCSMOQeBJ2RAnuFungnQOxLg|@BlenderOfficial|was_live|Big Buck Bunny\n", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            VideoSource source =
                    videoSourceProbe.probe("https://www.youtube.com/watch?v=aqz-KE-bpKQ").orElseThrow();

            assertThat(source.videoId()).isEqualTo("aqz-KE-bpKQ");
            assertThat(source.channelId()).isEqualTo("UCSMOQeBJ2RAnuFungnQOxLg");
            assertThat(source.title()).isEqualTo("Big Buck Bunny");
        }

        @Test
        @DisplayName("正常系：ダウンロードせずメタデータだけを取る引数で起動する")
        void testMethod02() throws Exception {
            Process process = mockProcess("youtube|abc123|UCxxxxxxxx|@foo|was_live|タイトル\n", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            videoSourceProbe.probe("https://www.youtube.com/watch?v=abc123");

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processLauncher).launch(captor.capture());
            List<String> command = captor.getValue();

            assertThat(command).first().isEqualTo("yt-dlp");
            assertThat(command).contains("--print", VideoSource.PRINT_TEMPLATE);
            // 再生リスト付きの URL を貼られても1本だけを対象にする
            assertThat(command).contains("--no-playlist", "--simulate");
            assertThat(command).last().isEqualTo("https://www.youtube.com/watch?v=abc123");
        }

        @Test
        @DisplayName("異常系：yt-dlpが異常終了した場合は空を返す")
        void testMethod03() throws Exception {
            // 存在しない動画・非公開の動画などがこれにあたる
            Process process = mockProcess("ERROR: Video unavailable\n", 1);
            when(processLauncher.launch(any())).thenReturn(process);

            Optional<VideoSource> result =
                    videoSourceProbe.probe("https://www.youtube.com/watch?v=unavailable");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("異常系：yt-dlpがインストールされていない場合は空を返す")
        void testMethod04() throws Exception {
            when(processLauncher.launch(any())).thenThrow(new IOException("yt-dlp: command not found"));

            assertThat(videoSourceProbe.probe("https://www.youtube.com/watch?v=abc123")).isEmpty();
        }

        @Test
        @DisplayName("異常系：想定と違う形式の出力は空を返す")
        void testMethod05() throws Exception {
            Process process = mockProcess("想定外の出力\n", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            assertThat(videoSourceProbe.probe("https://www.youtube.com/watch?v=abc123")).isEmpty();
        }
    }
}
