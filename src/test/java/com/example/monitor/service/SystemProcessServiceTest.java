package com.example.monitor.service;

import com.example.monitor.dto.SystemProcessesResponse.ProcessRow;
import com.example.monitor.dto.SystemProcessesResponse.RecordingInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import oshi.software.os.OSProcess;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OSHI で実際のプロセスを読まずに、プロセスの一覧と停止の判定（名前・確認用インスタンス・
 * 選べない理由・断る理由）を確かめる。
 *
 * <p>{@code MockitoExtension} を付けないのは、プロセスのモックを作るヘルパーが、判定によって
 * 呼ばれない getter までまとめてスタブするため（{@code ResourceMonitorServiceTest} と同じ）。
 */
@DisplayName("SystemProcessService")
class SystemProcessServiceTest {

    /** 親子の関係と名前だけを持つプロセス（パスと引数は読めなかったときの空）。 */
    private static OSProcess process(int pid, int parentPid, String name) {
        OSProcess process = mock(OSProcess.class);
        when(process.getProcessID()).thenReturn(pid);
        when(process.getParentProcessID()).thenReturn(parentPid);
        when(process.getName()).thenReturn(name);
        when(process.getPath()).thenReturn("");
        when(process.getArguments()).thenReturn(List.of());
        return process;
    }

    @Nested
    @DisplayName("displayName()")
    class DisplayNameOf {

        @Test
        @DisplayName("正常系：15 文字未満の名前はそのまま（yt-dlp はパスが python でも yt-dlp）")
        void testMethod01() {
            assertThat(SystemProcessService.displayName("yt-dlp", "/usr/bin/python3.12",
                    List.of("/usr/bin/python3", "/usr/local/bin/yt-dlp"))).isEqualTo("yt-dlp");
        }

        @Test
        @DisplayName("正常系：15 文字ちょうどで、パスのファイル名がその続きなら置き換える")
        void testMethod02() {
            assertThat(SystemProcessService.displayName("gnome-remote-de", "/usr/libexec/gnome-remote-desktop-daemon",
                    List.of("/usr/libexec/gnome-remote-desktop-daemon"))).isEqualTo("gnome-remote-desktop-daemon");
        }

        @Test
        @DisplayName("正常系：パスが読めなければ（空）、引数の先頭のファイル名で置き換える")
        void testMethod03() {
            assertThat(SystemProcessService.displayName("gnome-remote-de", "",
                    List.of("/usr/libexec/gnome-remote-desktop-daemon", "--headless")))
                    .isEqualTo("gnome-remote-desktop-daemon");
        }

        @Test
        @DisplayName("正常系：パスも引数の先頭も名前で始まらなければ、名前のまま")
        void testMethod04() {
            assertThat(SystemProcessService.displayName("gnome-remote-de", "/usr/bin/python3",
                    List.of("/usr/bin/python3", "daemon.py"))).isEqualTo("gnome-remote-de");
        }
    }

    @Nested
    @DisplayName("isSandbox()")
    class IsSandbox {

        @Test
        @DisplayName("正常系：java で -Dmonitor.scheduling.enabled=false を渡していれば true")
        void testMethod01() {
            assertThat(SystemProcessService.isSandbox(List.of("/usr/lib/jvm/java-21/bin/java",
                    "-Dmonitor.scheduling.enabled=false", "-jar", "build/libs/app.jar"))).isTrue();
        }

        @Test
        @DisplayName("正常系：その引数が無い java（本番）は false")
        void testMethod02() {
            assertThat(SystemProcessService.isSandbox(List.of("java", "-jar", "build/libs/app.jar"))).isFalse();
        }

        @Test
        @DisplayName("正常系：java でなければ、その引数があっても false")
        void testMethod03() {
            assertThat(SystemProcessService.isSandbox(List.of("bash", "-c",
                    "echo -Dmonitor.scheduling.enabled=false", "-Dmonitor.scheduling.enabled=false"))).isFalse();
        }
    }

    @Nested
    @DisplayName("lockedReasons()")
    class LockedReasons {

        private static final int SELF = 1000;

        @Test
        @DisplayName("正常系：アプリ自身には、自身の理由")
        void testMethod01() {
            Map<Integer, String> result = SystemProcessService.lockedReasons(
                    List.of(process(SELF, 900, "java")), SELF, Set.of(900, 1));

            assertThat(result).isEqualTo(Map.of(SELF, SystemProcessService.LOCKED_SELF));
        }

        @Test
        @DisplayName("正常系：アプリの祖先には、アプリの祖先の理由")
        void testMethod02() {
            Map<Integer, String> result = SystemProcessService.lockedReasons(
                    List.of(process(900, 1, "bash"), process(SELF, 900, "java")), SELF, Set.of(900, 1));

            assertThat(result).containsEntry(900, SystemProcessService.LOCKED_SELF_ANCESTOR);
        }

        @Test
        @DisplayName("正常系：gnome-shell には守るプロセスの理由、一覧の中のその親には守るプロセスの祖先の理由（子には付けない）")
        void testMethod03() {
            List<OSProcess> processes = List.of(process(400, 1, "launcher"), process(500, 400, "gnome-shell"),
                    process(600, 500, "bash"));

            Map<Integer, String> result = SystemProcessService.lockedReasons(processes, SELF, Set.of());

            assertThat(result).isEqualTo(Map.of(
                    500, SystemProcessService.LOCKED_PROTECTED,
                    400, SystemProcessService.LOCKED_PROTECTED_ANCESTOR));
        }

        @Test
        @DisplayName("正常系：アプリの祖先で、守るプロセスの祖先でもあれば、アプリの祖先の理由")
        void testMethod04() {
            List<OSProcess> processes = List.of(process(400, 1, "tmux"), process(500, 400, "gnome-shell"),
                    process(SELF, 400, "java"));

            Map<Integer, String> result = SystemProcessService.lockedReasons(processes, SELF, Set.of(400, 1));

            assertThat(result).containsEntry(400, SystemProcessService.LOCKED_SELF_ANCESTOR);
        }

        @Test
        @DisplayName("正常系：ふつうのプロセスは載せない")
        void testMethod05() {
            Map<Integer, String> result = SystemProcessService.lockedReasons(
                    List.of(process(700, 1, "sleep")), SELF, Set.of(900, 1));

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("refusal()")
    class Refusal {

        private static final long START_TIME = 1_700_000_000_000L;

        private static final String REFUSED_GONE = "このプロセスはすでに終了しています。";

        private static final String REFUSED_RECORDING = "録画のプロセスです。「録画を停止」から止めてください。";

        /** 一覧の行。断る理由が見るのは起動時刻・録画・選べない理由だけなので、ほかは決め打ち。 */
        private static ProcessRow row(RecordingInfo recording, String lockedReason) {
            return new ProcessRow(700, 1, START_TIME, "sleep", "sleep 600", "/tmp", null, 0L, 0L, List.of(),
                    null, null, recording, lockedReason, null);
        }

        @Test
        @DisplayName("異常系：一覧に無い（row が null）なら、すでに終了しています")
        void testMethod01() {
            assertThat(SystemProcessService.refusal(null, START_TIME, false)).isEqualTo(REFUSED_GONE);
        }

        @Test
        @DisplayName("異常系：起動時刻が違えば（PID の使い回し）、すでに終了しています")
        void testMethod02() {
            assertThat(SystemProcessService.refusal(row(null, null), START_TIME + 1, false)).isEqualTo(REFUSED_GONE);
        }

        @Test
        @DisplayName("異常系：選べない理由があれば、その理由")
        void testMethod03() {
            ProcessRow locked = row(null, SystemProcessService.LOCKED_PROTECTED);

            assertThat(SystemProcessService.refusal(locked, START_TIME, false))
                    .isEqualTo(SystemProcessService.LOCKED_PROTECTED);
        }

        @Test
        @DisplayName("異常系：録画のプロセス・子孫に録画のプロセスがあれば、録画の停止から止めるよう断る")
        void testMethod04() {
            RecordingInfo recording = new RecordingInfo(1L, "チャンネル", "配信", LocalDateTime.now(), 0L);

            assertThat(SystemProcessService.refusal(row(recording, null), START_TIME, false))
                    .isEqualTo(REFUSED_RECORDING);
            assertThat(SystemProcessService.refusal(row(null, null), START_TIME, true)).isEqualTo(REFUSED_RECORDING);
        }

        @Test
        @DisplayName("正常系：どれにも当たらなければ null（止めてよい）")
        void testMethod05() {
            assertThat(SystemProcessService.refusal(row(null, null), START_TIME, false)).isNull();
        }
    }
}
