package com.example.monitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DefaultProcessLauncher")
class DefaultProcessLauncherTest {

    /** 実行ファイルと引数だけを持つ ProcessHandle.Info。command が null なら実行ファイルを取れないプロセス。 */
    private static ProcessHandle.Info info(String command, String... arguments) {
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(info.command()).thenReturn(Optional.ofNullable(command));
        when(info.arguments()).thenReturn(Optional.of(arguments));
        return info;
    }

    @Nested
    @DisplayName("isWorkerProcess()")
    class IsWorkerProcess {

        @Test
        @DisplayName("正常系：実行ファイル名が yt-dlp・ffmpeg・ffprobe のプロセスは対象にする")
        void testMethod01() {
            ProcessHandle.Info ytDlp = info("/usr/local/bin/yt-dlp", "--live-from-start", "abc123");
            ProcessHandle.Info ffmpeg = info("/usr/bin/ffmpeg", "-i", "abc123.mp4");
            ProcessHandle.Info ffprobe = info("/usr/bin/ffprobe", "abc123.mp4");

            assertThat(DefaultProcessLauncher.isWorkerProcess(ytDlp)).isTrue();
            assertThat(DefaultProcessLauncher.isWorkerProcess(ffmpeg)).isTrue();
            assertThat(DefaultProcessLauncher.isWorkerProcess(ffprobe)).isTrue();
        }

        @Test
        @DisplayName("正常系：Python の処理系で、引数のファイル名が yt-dlp のプロセスは対象にする")
        void testMethod02() {
            ProcessHandle.Info python3 = info("/usr/bin/python3", "/usr/local/bin/yt-dlp", "--live-from-start", "abc123");
            ProcessHandle.Info python312 = info("/usr/bin/python3.12", "/usr/local/bin/yt-dlp", "abc123");

            assertThat(DefaultProcessLauncher.isWorkerProcess(python3)).isTrue();
            assertThat(DefaultProcessLauncher.isWorkerProcess(python312)).isTrue();
        }

        @Test
        @DisplayName("正常系：動画 ID を含むだけの grep・tail・シェルは対象にしない")
        void testMethod03() {
            ProcessHandle.Info grep = info("/usr/bin/grep", "abc123");
            ProcessHandle.Info tail = info("/usr/bin/tail", "-f", "logs/yt-dlp/abc123.log");
            ProcessHandle.Info bash = info("/usr/bin/bash", "-c", "yt-dlp abc123");

            assertThat(DefaultProcessLauncher.isWorkerProcess(grep)).isFalse();
            assertThat(DefaultProcessLauncher.isWorkerProcess(tail)).isFalse();
            assertThat(DefaultProcessLauncher.isWorkerProcess(bash)).isFalse();
        }

        @Test
        @DisplayName("正常系：Python の処理系でも、引数に yt-dlp が無いプロセスは対象にしない")
        void testMethod04() {
            ProcessHandle.Info script = info("/usr/bin/python3", "/home/user/tool.py", "abc123");
            ProcessHandle.Info helper = info("/usr/bin/python3", "/opt/yt-dlp-helper", "abc123");

            assertThat(DefaultProcessLauncher.isWorkerProcess(script)).isFalse();
            assertThat(DefaultProcessLauncher.isWorkerProcess(helper)).isFalse();
        }

        @Test
        @DisplayName("正常系：実行ファイルを取れないプロセスは対象にしない")
        void testMethod05() {
            ProcessHandle.Info unknown = info(null, "abc123");

            assertThat(DefaultProcessLauncher.isWorkerProcess(unknown)).isFalse();
        }
    }

    @Nested
    @DisplayName("isYtDlp()")
    class IsYtDlp {

        @Test
        @DisplayName("正常系：実行ファイル名が yt-dlp のプロセスは yt-dlp とみなす")
        void testMethod01() {
            ProcessHandle.Info ytDlp = info("/usr/local/bin/yt-dlp", "--live-from-start", "abc123");

            assertThat(DefaultProcessLauncher.isYtDlp(ytDlp)).isTrue();
        }

        @Test
        @DisplayName("正常系：Python の処理系で、引数のファイル名が yt-dlp のプロセスは yt-dlp とみなす")
        void testMethod02() {
            ProcessHandle.Info python3 = info("/usr/bin/python3", "/usr/local/bin/yt-dlp", "--live-from-start", "abc123");
            ProcessHandle.Info python312 = info("/usr/bin/python3.12", "/usr/local/bin/yt-dlp", "abc123");

            assertThat(DefaultProcessLauncher.isYtDlp(python3)).isTrue();
            assertThat(DefaultProcessLauncher.isYtDlp(python312)).isTrue();
        }

        @Test
        @DisplayName("正常系：ffmpeg・ffprobe は yt-dlp とみなさない（詰め替え中の ffmpeg まで止めないため）")
        void testMethod03() {
            ProcessHandle.Info ffmpeg = info("/usr/bin/ffmpeg", "-i", "abc123.mp4");
            ProcessHandle.Info ffprobe = info("/usr/bin/ffprobe", "abc123.mp4");

            assertThat(DefaultProcessLauncher.isYtDlp(ffmpeg)).isFalse();
            assertThat(DefaultProcessLauncher.isYtDlp(ffprobe)).isFalse();
        }

        @Test
        @DisplayName("正常系：動画 ID を含むだけの grep・tail・シェルと、引数に yt-dlp が無い Python は yt-dlp とみなさない")
        void testMethod04() {
            ProcessHandle.Info grep = info("/usr/bin/grep", "abc123");
            ProcessHandle.Info tail = info("/usr/bin/tail", "-f", "logs/yt-dlp/abc123.log");
            ProcessHandle.Info bash = info("/usr/bin/bash", "-c", "yt-dlp abc123");
            ProcessHandle.Info script = info("/usr/bin/python3", "/home/user/tool.py", "abc123");
            ProcessHandle.Info helper = info("/usr/bin/python3", "/opt/yt-dlp-helper", "abc123");

            assertThat(DefaultProcessLauncher.isYtDlp(grep)).isFalse();
            assertThat(DefaultProcessLauncher.isYtDlp(tail)).isFalse();
            assertThat(DefaultProcessLauncher.isYtDlp(bash)).isFalse();
            assertThat(DefaultProcessLauncher.isYtDlp(script)).isFalse();
            assertThat(DefaultProcessLauncher.isYtDlp(helper)).isFalse();
        }

        @Test
        @DisplayName("正常系：実行ファイルを取れないプロセスは yt-dlp とみなさない")
        void testMethod05() {
            ProcessHandle.Info unknown = info(null, "abc123");

            assertThat(DefaultProcessLauncher.isYtDlp(unknown)).isFalse();
        }
    }
}
