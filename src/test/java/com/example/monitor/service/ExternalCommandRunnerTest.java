package com.example.monitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultProcessLauncher} を使い、実際の OS プロセスを起動してテストする。
 *
 * <p>{@link ExternalCommandRunner} が直そうとしているのは「出力の読み取りとタイムアウトが
 * 実際に競合したときのタイミング」そのものなので、{@link ProcessLauncher} をモックにして
 * {@link Process} の振る舞いを人為的に決めてしまうと、直したい競合自体が
 * 再現できなくなる（{@code Process#waitFor(long, TimeUnit)} をモックで即座に {@code true} を
 * 返すようスタブすると、修正前のバグ——タイムアウトの行に構造上到達しない——が
 * モック越しには絶対に再現しない）。{@code VideoMetadataExtractorTest} や
 * {@code VideoSourceProbeTest} のように {@link ProcessLauncher} をモックにする方式は、
 * 「起動したコマンドライン」や「出力の解釈」を検証するのには向くが、
 * このクラス自身の並行処理の正しさは検証できない。そのためここでは
 * {@link DefaultProcessLauncher} を経由して実プロセスを使う。すべて数秒未満で終わる
 * コマンドのみを使い、テスト全体が遅くならないようにしている。
 */
@DisplayName("ExternalCommandRunner")
class ExternalCommandRunnerTest {

    private final ExternalCommandRunner runner = new ExternalCommandRunner(new DefaultProcessLauncher());

    @Nested
    @DisplayName("run(List, String, long)")
    class Run {

        @Test
        @DisplayName("異常系：無出力で応答しないプロセスは期限内に打ち切られる")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void testMethod01() {
            // sleep は標準出力に何も書かないまま指定秒数ブロックし続ける。
            //
            // 修正前は readLine() がパイプの EOF（＝プロセス終了）まで無期限に戻らないため、
            // タイムアウトを1秒に指定しても構造上その行に到達せず、sleep が実際に終わる3秒後まで
            // 待ってしまっていた（レビューで実測：3.01秒待ったうえで値ありの Optional が返る）。
            // このテストは修正前のコードに対しては失敗する（3秒待ったうえで値ありの Optional が返るため）。
            long startNanos = System.nanoTime();

            Optional<String> result = runner.run(List.of("sleep", "3"), "timeout-test", 1);

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertThat(result).isEmpty();
            // sleep(3秒)の終了を待っていないことを確認する。タイムアウト1秒に
            // 強制終了・後始末のオーバーヘッドを足しても3秒には遠く届かないはずである。
            assertThat(elapsedMillis).isLessThan(3000);
        }

        @Test
        @DisplayName("正常系：通常終了するプロセスの標準出力を取得できる")
        void testMethod02() {
            Optional<String> result = runner.run(List.of("echo", "hello"), "normal-test", 5);

            assertThat(result).contains("hello\n");
        }

        @Test
        @DisplayName("正常系：大量出力でもパイプが詰まらず完走する")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void testMethod03() {
            // OS のパイプバッファ（Linuxでは既定64KB程度）を大きく超える量を短時間で書き出させる。
            // 読み取りを完了待ちと並行させていないと、バッファが一杯になった時点で
            // プロセス側の書き込みがブロックし、waitFor も readLine も進まなくなる
            // （読み切ってから待つ設計・待ってから読む設計のどちらでも起きる）。
            Optional<String> result =
                    runner.run(List.of("bash", "-c", "yes | head -c 5000000"), "large-output-test", 10);

            assertThat(result).isPresent();
            // "y\n" の繰り返し（1行2バイト）なので、5,000,000バイトからそう変わらない長さになる
            assertThat(result.get().length()).isGreaterThan(4_000_000);
        }
    }
}
