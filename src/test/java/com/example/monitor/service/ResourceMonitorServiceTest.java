package com.example.monitor.service;

import com.example.monitor.dto.ResourceHistoryPoint;
import com.example.monitor.dto.ResourceSnapshotResponse.ApplicationUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.HelperUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.ProcessUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.RecorderUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.ServiceUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.SystemUsage;
import com.example.monitor.dto.ResourceSnapshotResponse.Warning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import oshi.software.os.OSProcess;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OSHI の実測を通さず、切り出した判定・集計（注意・合計・録画プロセスの見分け・用途・CPU 使用率の窓）を確かめる。
 *
 * <p>{@code MockitoExtension} を付けないのは、OSHI のプロセスのモックを作るヘルパーが、分岐によって呼ばれない getter まで
 * まとめてスタブするため（厳密スタブの検査を受けると {@code UnnecessaryStubbingException} になる）。
 */
@DisplayName("ResourceMonitorService")
class ResourceMonitorServiceTest {

    /** 端末全体の値。CPU・負荷・ネットワークは注意の判定に使わないので null にする。 */
    private static SystemUsage system(long memoryTotal, long memoryAvailable, long swapTotal, long swapUsed,
                                      Long diskTotal, Long diskFree) {
        return new SystemUsage(null, 4, null, memoryTotal, memoryTotal - memoryAvailable, memoryAvailable,
                swapTotal, swapUsed, "recordings", diskTotal, diskFree, null, null);
    }

    /** どれも目安の内側の値（メモリ 10GB 中 5GB 空き・スワップなし・保存先 1TB 中 500GB 空き）。 */
    private static SystemUsage healthySystem() {
        return system(10_000_000_000L, 5_000_000_000L, 0L, 0L, 1_000_000_000_000L, 500_000_000_000L);
    }

    /** 推移を新しい順に並べる。引数は端末全体の CPU 使用率（先頭が今回の記録）。 */
    private static List<ResourceHistoryPoint> recent(Double... systemCpuPercents) {
        return Arrays.stream(systemCpuPercents)
                .map(cpu -> new ResourceHistoryPoint(LocalDateTime.now(), cpu, 50.0, null, 0L, 0, null))
                .toList();
    }

    /** 親子の関係と引数だけを持つプロセス。 */
    private static OSProcess process(int pid, int parentPid, String name, List<String> args) {
        OSProcess process = mock(OSProcess.class);
        when(process.getProcessID()).thenReturn(pid);
        when(process.getParentProcessID()).thenReturn(parentPid);
        when(process.getName()).thenReturn(name);
        when(process.getArguments()).thenReturn(args);
        when(process.getResidentMemory()).thenReturn(0L);
        return process;
    }

    /** CPU 時間を持つプロセス（ミリ秒）。 */
    private static OSProcess cpuProcess(int pid, long startTime, long kernelMillis, long userMillis, long upTimeMillis) {
        OSProcess process = mock(OSProcess.class);
        when(process.getProcessID()).thenReturn(pid);
        when(process.getStartTime()).thenReturn(startTime);
        when(process.getKernelTime()).thenReturn(kernelMillis);
        when(process.getUserTime()).thenReturn(userMillis);
        when(process.getUpTime()).thenReturn(upTimeMillis);
        return process;
    }

    /** 引数と作業ディレクトリを持つプロセス。 */
    private static OSProcess commandProcess(List<String> args, String workingDirectory) {
        OSProcess process = mock(OSProcess.class);
        when(process.getArguments()).thenReturn(args);
        when(process.getCurrentWorkingDirectory()).thenReturn(workingDirectory);
        return process;
    }

    @Nested
    @DisplayName("evaluateWarnings()")
    class EvaluateWarnings {

        @Test
        @DisplayName("正常系：どれも目安の内側なら注意は空")
        void testMethod01() {
            List<Warning> result = ResourceMonitorService.evaluateWarnings(healthySystem(),
                    recent(10.0, 10.0, 10.0, 10.0, 10.0));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("異常系：直近 5 件の CPU がすべて 85% を超えると cpu の注意を出す")
        void testMethod02() {
            List<Warning> result = ResourceMonitorService.evaluateWarnings(healthySystem(),
                    recent(90.0, 86.0, 99.0, 90.0, 85.1));

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().key()).isEqualTo("cpu");
            assertThat(result.getFirst().message()).isEqualTo("CPU 使用率が 5 分以上 85% を超えています");
        }

        @Test
        @DisplayName("正常系：85% ちょうどが 1 件でも混ざれば cpu の注意を出さない")
        void testMethod03() {
            List<Warning> result = ResourceMonitorService.evaluateWarnings(healthySystem(),
                    recent(90.0, 90.0, 85.0, 90.0, 90.0));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系：記録が 4 件しかない起動直後は、すべて超えていても cpu の注意を出さない")
        void testMethod04() {
            List<Warning> result = ResourceMonitorService.evaluateWarnings(healthySystem(),
                    recent(99.0, 99.0, 99.0, 99.0));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系：CPU が分からない（null）記録が混ざれば cpu の注意を出さない")
        void testMethod05() {
            List<Warning> result = ResourceMonitorService.evaluateWarnings(healthySystem(),
                    recent(90.0, null, 90.0, 90.0, 90.0));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系：6 件目より古い記録が低くても、新しい 5 件がすべて超えていれば cpu の注意を出す")
        void testMethod06() {
            List<Warning> result = ResourceMonitorService.evaluateWarnings(healthySystem(),
                    recent(90.0, 90.0, 90.0, 90.0, 90.0, 10.0));

            assertThat(result).extracting(Warning::key).containsExactly("cpu");
        }

        @Test
        @DisplayName("異常系：空きメモリが合計の 10% を下回ると memory の注意を出す")
        void testMethod07() {
            SystemUsage system = system(10_000_000_000L, 900_000_000L, 0L, 0L,
                    1_000_000_000_000L, 500_000_000_000L);

            List<Warning> result = ResourceMonitorService.evaluateWarnings(system, recent());

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().key()).isEqualTo("memory");
            // GB の数値は既定のロケールで小数点が変わるので、先頭だけを比べる
            assertThat(result.getFirst().message()).startsWith("空きメモリが 10% を下回っています（");
        }

        @Test
        @DisplayName("異常系：スワップの使用が合計の半分を超えると swap の注意を出す")
        void testMethod08() {
            SystemUsage system = system(10_000_000_000L, 5_000_000_000L, 4_000_000_000L, 2_500_000_000L,
                    1_000_000_000_000L, 500_000_000_000L);

            List<Warning> result = ResourceMonitorService.evaluateWarnings(system, recent());

            assertThat(result).extracting(Warning::key).containsExactly("swap");
        }

        @Test
        @DisplayName("正常系：スワップが無い（合計 0）端末では swap の注意を出さない")
        void testMethod09() {
            SystemUsage system = system(10_000_000_000L, 5_000_000_000L, 0L, 1L,
                    1_000_000_000_000L, 500_000_000_000L);

            List<Warning> result = ResourceMonitorService.evaluateWarnings(system, recent());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("異常系：録画の保存先の空きが合計の 10% を下回ると disk の注意を出す")
        void testMethod10() {
            SystemUsage system = system(10_000_000_000L, 5_000_000_000L, 0L, 0L,
                    1_000_000_000_000L, 50_000_000_000L);

            List<Warning> result = ResourceMonitorService.evaluateWarnings(system, recent());

            assertThat(result).extracting(Warning::key).containsExactly("disk");
        }

        @Test
        @DisplayName("正常系：保存先の容量が取れない（合計か空きが null）ときは disk の注意を出さない")
        void testMethod11() {
            SystemUsage noTotal = system(10_000_000_000L, 5_000_000_000L, 0L, 0L, null, 0L);
            SystemUsage noFree = system(10_000_000_000L, 5_000_000_000L, 0L, 0L, 1_000_000_000_000L, null);

            assertThat(ResourceMonitorService.evaluateWarnings(noTotal, recent())).isEmpty();
            assertThat(ResourceMonitorService.evaluateWarnings(noFree, recent())).isEmpty();
        }

        @Test
        @DisplayName("正常系：複数の注意は cpu・memory・swap・disk の順に並ぶ")
        void testMethod12() {
            SystemUsage system = system(10_000_000_000L, 900_000_000L, 4_000_000_000L, 2_500_000_000L,
                    1_000_000_000_000L, 50_000_000_000L);

            List<Warning> result = ResourceMonitorService.evaluateWarnings(system,
                    recent(90.0, 90.0, 90.0, 90.0, 90.0));

            assertThat(result).extracting(Warning::key).containsExactly("cpu", "memory", "swap", "disk");
        }
    }

    @Nested
    @DisplayName("add()")
    class Add {

        @Test
        @DisplayName("正常系：両方あれば足す")
        void testMethod01() {
            assertThat(ResourceMonitorService.add(1.5, 2.25)).isEqualTo(3.75);
        }

        @Test
        @DisplayName("正常系：どちらかが分からない（null）なら合計も null（0 とみなして小さく見せない）")
        void testMethod02() {
            assertThat(ResourceMonitorService.add(null, 1.0)).isNull();
            assertThat(ResourceMonitorService.add(1.0, null)).isNull();
        }
    }

    @Nested
    @DisplayName("recordingVideoId()")
    class RecordingVideoId {

        private static final String WATCH_URL = "https://www.youtube.com/watch?v=abcdEFGhijk";

        /** 録画の yt-dlp の引数（testMethod01 と同じ形）。出力先（{@code -o} の値）だけを差し替える。 */
        private static List<String> recordingArgs(String output) {
            return List.of("python3", "/usr/local/bin/yt-dlp", "--no-part", "-o", output, WATCH_URL);
        }

        @Test
        @DisplayName("正常系：作業ディレクトリを起点にした相対の出力先が録画の保存先の中なら、ファイル名の先頭の動画 ID を返す")
        void testMethod01(@TempDir Path tempDir) {
            Path root = tempDir.resolve("recordings").toAbsolutePath().normalize();
            OSProcess process = commandProcess(
                    recordingArgs(Path.of("recordings", "UC123", "abcdEFGhijk.%(ext)s").toString()),
                    tempDir.toString());

            assertThat(ResourceMonitorService.recordingVideoId(process, root)).isEqualTo("abcdEFGhijk");
        }

        @Test
        @DisplayName("正常系：出力先が絶対パスなら、作業ディレクトリが取れなくても（null）判定できる")
        void testMethod02(@TempDir Path tempDir) {
            Path root = tempDir.resolve("recordings").toAbsolutePath().normalize();
            OSProcess process = commandProcess(
                    recordingArgs(root.resolve("UC123").resolve("abcdEFGhijk.%(ext)s").toString()), null);

            assertThat(ResourceMonitorService.recordingVideoId(process, root)).isEqualTo("abcdEFGhijk");
        }

        @Test
        @DisplayName("異常系：出力先が録画の保存先の外（端末保存の置き場所）なら録画プロセスとみなさない")
        void testMethod03(@TempDir Path tempDir) {
            Path root = tempDir.resolve("recordings").toAbsolutePath().normalize();
            String output = tempDir.resolve("data").resolve("device-downloads").resolve("job1")
                    .resolve("abcdEFGhijk.%(ext)s").toString();
            OSProcess process = commandProcess(recordingArgs(output), tempDir.toString());

            assertThat(ResourceMonitorService.recordingVideoId(process, root)).isNull();
        }

        @Test
        @DisplayName("異常系：.. で録画の保存先の外に出る出力先は録画プロセスとみなさない")
        void testMethod04(@TempDir Path tempDir) {
            Path root = tempDir.resolve("recordings").toAbsolutePath().normalize();
            OSProcess process = commandProcess(
                    recordingArgs(Path.of("recordings", "..", "outside", "abcdEFGhijk.%(ext)s").toString()),
                    tempDir.toString());

            assertThat(ResourceMonitorService.recordingVideoId(process, root)).isNull();
        }

        @Test
        @DisplayName("異常系：引数に yt-dlp を含まないプロセスは、出力先が保存先の中でも録画プロセスとみなさない")
        void testMethod05(@TempDir Path tempDir) {
            Path root = tempDir.resolve("recordings").toAbsolutePath().normalize();
            OSProcess process = commandProcess(
                    List.of("ffmpeg", "-i", "in.mp4", "-o", root.resolve("UC123").resolve("abcdEFGhijk.mp4").toString()),
                    tempDir.toString());

            assertThat(ResourceMonitorService.recordingVideoId(process, root)).isNull();
        }

        @Test
        @DisplayName("異常系：-o が無いプロセスは録画プロセスとみなさない")
        void testMethod06(@TempDir Path tempDir) {
            Path root = tempDir.resolve("recordings").toAbsolutePath().normalize();
            OSProcess process = commandProcess(List.of("yt-dlp", "--version"), tempDir.toString());

            assertThat(ResourceMonitorService.recordingVideoId(process, root)).isNull();
        }

        @Test
        @DisplayName("異常系：-o が最後の引数で値が無ければ録画プロセスとみなさない")
        void testMethod07(@TempDir Path tempDir) {
            Path root = tempDir.resolve("recordings").toAbsolutePath().normalize();
            OSProcess process = commandProcess(List.of("yt-dlp", "-o"), tempDir.toString());

            assertThat(ResourceMonitorService.recordingVideoId(process, root)).isNull();
        }

        @Test
        @DisplayName("異常系：パスとして読めない出力先（NUL 文字を含む）は例外にせず録画プロセスとみなさない")
        void testMethod08(@TempDir Path tempDir) {
            Path root = tempDir.resolve("recordings").toAbsolutePath().normalize();
            OSProcess process = commandProcess(recordingArgs("recordings/bad\0name.mp4"), tempDir.toString());

            assertThatCode(() -> ResourceMonitorService.recordingVideoId(process, root)).doesNotThrowAnyException();
            assertThat(ResourceMonitorService.recordingVideoId(process, root)).isNull();
        }
    }

    @Nested
    @DisplayName("helperPurpose()")
    class HelperPurpose {

        @Test
        @DisplayName("正常系：フォルダ選択のプロセスは、スクリプトに yt-dlp の語を含んでも「フォルダの選択」")
        void testMethod01() {
            assertThat(ResourceMonitorService.helperPurpose(List.of("powershell", "-Command", "C:\\yt-dlp\\recordings")))
                    .isEqualTo("フォルダの選択");
            assertThat(ResourceMonitorService.helperPurpose(List.of("zenity", "--file-selection")))
                    .isEqualTo("フォルダの選択");
        }

        @Test
        @DisplayName("正常系：-o 付きの yt-dlp は「端末に保存」")
        void testMethod02() {
            List<String> args = List.of("python3", "/usr/local/bin/yt-dlp", "-o", "data/device-downloads/job1/x.%(ext)s",
                    "https://www.youtube.com/watch?v=abcdEFGhijk");

            assertThat(ResourceMonitorService.helperPurpose(args)).isEqualTo("端末に保存");
        }

        @Test
        @DisplayName("正常系：-o 無しの yt-dlp は「動画の URL の確認」")
        void testMethod03() {
            List<String> args = List.of("yt-dlp", "--dump-json", "https://www.youtube.com/watch?v=abcdEFGhijk");

            assertThat(ResourceMonitorService.helperPurpose(args)).isEqualTo("動画の URL の確認");
        }

        @Test
        @DisplayName("正常系：PCM を出す ffmpeg は「耳キスの検出」")
        void testMethod04() {
            List<String> args = List.of("nice", "-n", "19", "ffmpeg", "-i", "in.mp4", "-f", "s16le", "-");

            assertThat(ResourceMonitorService.helperPurpose(args)).isEqualTo("耳キスの検出");
        }

        @Test
        @DisplayName("正常系：1 コマだけ書き出す ffmpeg は「サムネイルの作成」")
        void testMethod05() {
            List<String> args = List.of("ffmpeg", "-ss", "10", "-i", "in.mp4", "-frames:v", "1", "out.jpg");

            assertThat(ResourceMonitorService.helperPurpose(args)).isEqualTo("サムネイルの作成");
        }

        @Test
        @DisplayName("正常系：コピーで詰め替える ffmpeg は「MP4 への詰め替え」")
        void testMethod06() {
            List<String> args = List.of("ffmpeg", "-i", "in.mp4", "-c", "copy", "out.mp4");

            assertThat(ResourceMonitorService.helperPurpose(args)).isEqualTo("MP4 への詰め替え");
        }

        @Test
        @DisplayName("正常系：ffprobe は「動画ファイルの確認」")
        void testMethod07() {
            List<String> args = List.of("ffprobe", "-v", "error", "in.mp4");

            assertThat(ResourceMonitorService.helperPurpose(args)).isEqualTo("動画ファイルの確認");
        }

        @Test
        @DisplayName("正常系：見分けられないもの・引数が取れないものは「その他」")
        void testMethod08() {
            assertThat(ResourceMonitorService.helperPurpose(List.of("sleep", "10"))).isEqualTo("その他");
            assertThat(ResourceMonitorService.helperPurpose(List.of())).isEqualTo("その他");
        }
    }

    @Nested
    @DisplayName("CpuMeter.percent()")
    class CpuMeterPercent {

        /** 前回の記録。CPU 使用率の計算が見るのはプロセスの表だけなので、ほかは 0 にする。 */
        private static ResourceMonitorService.Baseline baseline(Map<Integer, OSProcess> processes) {
            return new ResourceMonitorService.Baseline(0L, new long[0], 0L, 0L, processes);
        }

        @Test
        @DisplayName("正常系：差を取る窓が無い（起動直後の計測）ときは null を返し、次の差分のためにプロセスを覚える")
        void testMethod01() {
            Map<Integer, OSProcess> tracked = new HashMap<>();
            ResourceMonitorService.CpuMeter meter = new ResourceMonitorService.CpuMeter(null, false, 0L, 4, tracked);
            OSProcess process = cpuProcess(100, 1000L, 1000L, 1000L, 5000L);

            assertThat(meter.percent(process)).isNull();
            assertThat(tracked).containsEntry(100, process);
        }

        @Test
        @DisplayName("正常系：前回の記録にも同じプロセス（PID と開始時刻が同じ）があれば、CPU 時間の差を窓の長さとコア数で割る")
        void testMethod02() {
            OSProcess before = cpuProcess(100, 1000L, 4000L, 6000L, 0L);
            OSProcess now = cpuProcess(100, 1000L, 10000L, 24000L, 0L);
            ResourceMonitorService.CpuMeter meter = new ResourceMonitorService.CpuMeter(
                    baseline(Map.of(100, before)), true, 60000L, 4, new HashMap<>());

            // (34000 − 10000) ÷ 60000 ÷ 4 × 100
            assertThat(meter.percent(now)).isCloseTo(10.0, within(1e-9));
        }

        @Test
        @DisplayName("正常系：PID が同じでも開始時刻が違えば別のプロセスとみなし、起動からの累計を起動からの時間で割る")
        void testMethod03() {
            OSProcess before = cpuProcess(100, 1000L, 4000L, 6000L, 0L);
            OSProcess now = cpuProcess(100, 5000L, 1000L, 2000L, 30000L);
            ResourceMonitorService.CpuMeter meter = new ResourceMonitorService.CpuMeter(
                    baseline(Map.of(100, before)), true, 60000L, 2, new HashMap<>());

            // 3000 ÷ 30000 ÷ 2 × 100
            assertThat(meter.percent(now)).isCloseTo(5.0, within(1e-9));
        }

        @Test
        @DisplayName("正常系：前回の記録の後に始まったプロセスは、起動からの累計を起動からの時間で割る")
        void testMethod04() {
            OSProcess now = cpuProcess(200, 1000L, 2000L, 4000L, 20000L);
            ResourceMonitorService.CpuMeter meter = new ResourceMonitorService.CpuMeter(
                    baseline(Map.of()), true, 60000L, 1, new HashMap<>());

            assertThat(meter.percent(now)).isCloseTo(30.0, within(1e-9));
        }

        @Test
        @DisplayName("正常系：前回に無いプロセスでも、起動からの時間が窓より長ければ窓の長さで割る")
        void testMethod05() {
            OSProcess now = cpuProcess(200, 1000L, 6000L, 6000L, 600000L);
            ResourceMonitorService.CpuMeter meter = new ResourceMonitorService.CpuMeter(
                    baseline(Map.of()), true, 60000L, 2, new HashMap<>());

            assertThat(meter.percent(now)).isCloseTo(10.0, within(1e-9));
        }

        @Test
        @DisplayName("異常系：起動からの時間が 0 のプロセスは null（0 で割らない）")
        void testMethod06() {
            OSProcess now = cpuProcess(200, 1000L, 0L, 0L, 0L);
            ResourceMonitorService.CpuMeter meter = new ResourceMonitorService.CpuMeter(
                    baseline(Map.of()), true, 60000L, 1, new HashMap<>());

            assertThat(meter.percent(now)).isNull();
        }
    }

    @Nested
    @DisplayName("helperUsages()")
    class HelperUsages {

        private static final int APP_PID = 1000;

        /** 録画の yt-dlp とその子、耳キスの ffmpeg、端末保存の yt-dlp とその子。 */
        private static List<OSProcess> processes() {
            OSProcess recorder = process(2000, APP_PID, "yt-dlp", List.of("python3", "/usr/local/bin/yt-dlp", "-o",
                    "recordings/UC123/abcdEFGhijk.%(ext)s", "https://www.youtube.com/watch?v=abcdEFGhijk"));
            OSProcess recorderChild = process(2001, 2000, "ffmpeg", List.of("ffmpeg", "-i", "-"));
            OSProcess earKiss = process(3000, APP_PID, "ffmpeg",
                    List.of("nice", "-n", "19", "ffmpeg", "-i", "in.mp4", "-f", "s16le", "-"));
            OSProcess deviceDownload = process(4000, APP_PID, "yt-dlp", List.of("python3", "/usr/local/bin/yt-dlp", "-o",
                    "data/device-downloads/job1/x.%(ext)s", "https://www.youtube.com/watch?v=abcdEFGhijk"));
            OSProcess deviceDownloadChild = process(4001, 4000, "ffmpeg", List.of("ffmpeg", "-i", "-"));
            return List.of(recorder, recorderChild, earKiss, deviceDownload, deviceDownloadChild);
        }

        /** 録画プロセスとして数えたもの（2000 とその子の 2001）。 */
        private static List<RecorderUsage> recorders() {
            return List.of(new RecorderUsage(2000, "yt-dlp", "チャンネル", null, 0L,
                    List.of(new ProcessUsage(2001, "ffmpeg", null, 0L))));
        }

        private static List<HelperUsage> helperUsages(List<OSProcess> processes, List<RecorderUsage> recorders) {
            // measure() と同じ組み立て方
            Map<Integer, List<OSProcess>> childrenByParent = processes.stream()
                    .collect(Collectors.groupingBy(OSProcess::getParentProcessID));
            ResourceMonitorService.CpuMeter cpu = new ResourceMonitorService.CpuMeter(null, false, 0L, 4, new HashMap<>());
            return ResourceMonitorService.helperUsages(processes, APP_PID, recorders, childrenByParent, cpu);
        }

        @Test
        @DisplayName("正常系：アプリの直接の子ごとにまとめ、その先の子孫を children に入れる")
        void testMethod01() {
            List<HelperUsage> result = helperUsages(processes(), recorders());

            assertThat(result).extracting(HelperUsage::pid).containsExactlyInAnyOrder(3000, 4000);
            HelperUsage deviceDownload = result.stream().filter(helper -> helper.pid() == 4000).findFirst().orElseThrow();
            HelperUsage earKiss = result.stream().filter(helper -> helper.pid() == 3000).findFirst().orElseThrow();
            assertThat(deviceDownload.children()).extracting(ProcessUsage::pid).containsExactly(4001);
            assertThat(deviceDownload.purpose()).isEqualTo("端末に保存");
            assertThat(earKiss.purpose()).isEqualTo("耳キスの検出");
        }

        @Test
        @DisplayName("正常系：録画プロセスとその子孫として数えたものは、アプリの子でも入れない（二重に数えない）")
        void testMethod02() {
            List<HelperUsage> result = helperUsages(processes(), recorders());

            assertThat(result).extracting(HelperUsage::pid).doesNotContain(2000, 2001);
            assertThat(result.stream().flatMap(helper -> helper.children().stream()).map(ProcessUsage::pid))
                    .doesNotContain(2000, 2001);
        }

        @Test
        @DisplayName("正常系：アプリの子でないプロセス（アプリの再起動前から動く録画の yt-dlp など）は入れない")
        void testMethod03() {
            OSProcess orphan = process(5000, 1, "yt-dlp", List.of("yt-dlp", "-o", "x"));

            List<HelperUsage> result = helperUsages(List.of(orphan), List.of());

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("serviceUsage()")
    class ServiceUsageTotal {

        private static final ApplicationUsage APPLICATION = new ApplicationUsage(1000, 1.0, 100L, 0L, 0L, 10);

        @Test
        @DisplayName("正常系：アプリ本体・録画プロセス・その他の外部プロセスと、それぞれの子孫を合計する")
        void testMethod01() {
            List<RecorderUsage> recorders = List.of(new RecorderUsage(2000, "yt-dlp", "チャンネル", 2.0, 200L,
                    List.of(new ProcessUsage(2001, "ffmpeg", 0.5, 50L))));
            List<HelperUsage> helpers = List.of(new HelperUsage(3000, "yt-dlp", "端末に保存", 3.0, 300L,
                    List.of(new ProcessUsage(3001, "ffmpeg", 0.25, 25L))));

            ServiceUsage result = ResourceMonitorService.serviceUsage(APPLICATION, recorders, helpers);

            assertThat(result.cpuPercent()).isEqualTo(6.75);
            assertThat(result.memoryBytes()).isEqualTo(675L);
            assertThat(result.recorders()).isSameAs(recorders);
            assertThat(result.helpers()).isSameAs(helpers);
        }

        @Test
        @DisplayName("正常系：CPU 使用率が 1 つでも分からなければ合計も null、実メモリは合計する")
        void testMethod02() {
            List<RecorderUsage> recorders = List.of(new RecorderUsage(2000, "yt-dlp", "チャンネル", 2.0, 200L,
                    List.of(new ProcessUsage(2001, "ffmpeg", 0.5, 50L))));
            List<HelperUsage> helpers = List.of(new HelperUsage(3000, "yt-dlp", "端末に保存", 3.0, 300L,
                    List.of(new ProcessUsage(3001, "ffmpeg", null, 25L))));

            ServiceUsage result = ResourceMonitorService.serviceUsage(APPLICATION, recorders, helpers);

            assertThat(result.cpuPercent()).isNull();
            assertThat(result.memoryBytes()).isEqualTo(675L);
        }

        @Test
        @DisplayName("正常系：録画プロセスもその他も無ければアプリ本体の値のまま")
        void testMethod03() {
            ServiceUsage result = ResourceMonitorService.serviceUsage(APPLICATION, List.of(), List.of());

            assertThat(result.cpuPercent()).isEqualTo(1.0);
            assertThat(result.memoryBytes()).isEqualTo(100L);
        }
    }
}
