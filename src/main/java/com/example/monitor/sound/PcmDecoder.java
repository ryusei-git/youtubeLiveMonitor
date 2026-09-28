package com.example.monitor.sound;

import com.example.monitor.util.ProcessTermination;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 録画ファイルの音声を ffmpeg でデコードし、{@link EarKissDetector} の入力（s16le・2ch・32kHz）として読めるようにする。
 *
 * <h2>{@code nice -n 19} で動かす理由</h2>
 * 検出は急がない後回しの処理で、録画（yt-dlp・ffmpeg）と本番のサービスの方が大事。デコードは 2 時間の録画で
 * CPU を 8 秒ほど使う（Issue #466 の実測）ので、優先度をいちばん下げて CPU を譲る。{@code nice} は ffmpeg を exec で起動し直すので、
 * {@link Process} はそのまま ffmpeg を指す（止めるときに ffmpeg が残らない）。
 * なお、検出の計算（FFT）は JVM の中で動くので、{@code nice} は効かない。
 *
 * <h2>ffmpeg の出力をパイプで読む理由</h2>
 * {@code docs/pitfalls.md}「外部プロセスの出力を JVM へのパイプにすると、再起動で yt-dlp が止まる」は、
 * JVM より長く動き続けるべき録画のための決まり。検出は JVM の中で出力を読みながら計算するので、JVM が止まれば
 * 読み手がいなくなって ffmpeg も止まってよい（止まってほしい）。ファイルに書き出すと、2 時間で 900MB の
 * 一時ファイルができ、止めた後の掃除も要る。
 *
 * <h2>標準エラーを読み、失敗の理由に入れる理由</h2>
 * 以前は JVM の標準エラーへそのまま流していたが、本番では {@code logs/service.log} に録画の番号なしで混ざり、
 * 実行記録の理由（{@code message}）には「終了コード N」しか残らず、なぜ失敗したか分からなかった。
 * そこで読み手のスレッドで末尾だけを持ち、失敗したときの {@link IOException} の文言に入れる。
 * 検出の失敗のログ（録画の番号つき）・実行記録・CLI の画面（{@code sound detect}）に、そのまま理由が出る。
 * 成功したときの出力は捨てる（{@code -loglevel error} で成功したなら、読み飛ばした壊れたフレームなどで、検出の結果は使える）。
 * 最後まで読み続けるのは、読まないパイプにすると、壊れた入力でエラーが続いたときにパイプが詰まり、ffmpeg が止まって
 * 読み込みも進まなくなるため。
 */
public final class PcmDecoder {

    /** ffmpeg の標準エラーのうち、読み手のスレッドが持つ末尾の長さ（バイト）。 */
    private static final int STDERR_TAIL_BYTES = 4096;

    /**
     * 失敗の理由に入れる標準エラーの末尾の長さ（文字）。実行記録の理由は先頭から 500 文字までしか残らないので、
     * 文言ではパスより前に置き、この長さに収めて、失敗の理由がふつう書かれる最後の行を残す。
     */
    private static final int STDERR_MESSAGE_CHARS = 300;

    /** 失敗したとき、標準エラーを読み終えるまで待つ上限。ffmpeg は終わっているので、ふつうはすぐ読み終わる。 */
    private static final Duration STDERR_JOIN = Duration.ofSeconds(1);

    private PcmDecoder() {
    }

    /**
     * ffmpeg を起動し、デコードした PCM を読むストリームを返す。
     *
     * <p>{@code -ss}・{@code -to} は入力の側に付ける（入力の時刻で指定し、開始位置までは読み飛ばす）。
     * 返したストリームは、最後まで読むと ffmpeg の終了コードを確かめ、0 以外なら {@link IOException} を投げる
     * （文言に ffmpeg の標準エラーの末尾を入れる）
     * （途中で失敗したのに、そこまでの音声だけで「候補なし」と見誤らないため）。閉じると ffmpeg を止める。
     *
     * @param file        録画ファイル
     * @param fromSeconds 開始位置（秒）。{@code null} なら先頭から
     * @param toSeconds   終了位置（秒。録画の先頭から数える）。{@code null} なら最後まで
     * @return PCM（s16le・2ch・32kHz）のストリーム。閉じるのは呼び出し側
     * @throws IOException ffmpeg を起動できなかった場合
     */
    public static InputStream open(Path file, Double fromSeconds, Double toSeconds) throws IOException {
        List<String> command = new ArrayList<>(List.of("nice", "-n", "19", "ffmpeg", "-nostdin", "-loglevel", "error"));
        if (fromSeconds != null) {
            command.addAll(List.of("-ss", seconds(fromSeconds)));
        }
        if (toSeconds != null) {
            command.addAll(List.of("-to", seconds(toSeconds)));
        }
        command.addAll(List.of("-i", file.toString(), "-vn",
                "-ac", String.valueOf(EarKissDetector.CHANNELS),
                "-ar", String.valueOf(EarKissDetector.SAMPLE_RATE),
                "-f", "s16le", "-"));
        // 標準エラーは既定のパイプのまま受け、StderrTail が最後まで読む（クラスの JavaDoc）
        Process process = new ProcessBuilder(command).start();
        StderrTail stderr = new StderrTail(process.getErrorStream());
        Thread stderrReader = Thread.ofVirtual().name("ffmpeg-stderr").start(stderr);
        return new DecodedStream(process, file, stderr, stderrReader);
    }

    /** ffmpeg に渡す秒数。{@code Double.toString} は大きい値を指数表記（{@code 1.0E7}）にし、ffmpeg が読めないため。 */
    private static String seconds(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** ffmpeg の標準出力。最後まで読んだら終了コードを確かめ、閉じたら ffmpeg を止める。 */
    private static final class DecodedStream extends FilterInputStream {

        private final Process process;
        private final Path file;
        private final StderrTail stderr;
        private final Thread stderrReader;

        DecodedStream(Process process, Path file, StderrTail stderr, Thread stderrReader) {
            super(process.getInputStream());
            this.process = process;
            this.file = file;
            this.stderr = stderr;
            this.stderrReader = stderrReader;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) {
                checkExitCode();
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n < 0) {
                checkExitCode();
            }
            return n;
        }

        private void checkExitCode() throws IOException {
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("ffmpeg の終了を待つ間に中断されました: " + file);
            }
            if (exitCode != 0) {
                throw new IOException("ffmpeg が終了コード " + exitCode + " で失敗しました" + stderrSuffix() + ": " + file);
            }
        }

        /**
         * 失敗の理由に添える ffmpeg の標準エラーの末尾（{@code STDERR_MESSAGE_CHARS} 文字まで）。ffmpeg は終わっているので
         * 読み手もすぐ EOF で終わるが、待ち過ぎないよう {@code STDERR_JOIN} で打ち切り、それまでに読めた分を使う。
         */
        private String stderrSuffix() {
            try {
                stderrReader.join(STDERR_JOIN);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String text = stderr.text();
            if (text.isEmpty()) {
                return "";
            }
            if (text.length() > STDERR_MESSAGE_CHARS) {
                text = "…" + text.substring(text.length() - STDERR_MESSAGE_CHARS);
            }
            return "（ffmpeg の出力: " + text + "）";
        }

        @Override
        public void close() throws IOException {
            // 途中でやめたときに ffmpeg を残さない（読み終えた後なら既に終わっている）。パイプより先に止めるのは、
            // 先にパイプを閉じると ffmpeg が書き込みの失敗（Broken pipe）を標準エラーに出してから終わるため
            boolean interrupted = ProcessTermination.destroyForciblyAndAwait(process);
            try {
                super.close();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * ffmpeg の標準エラーを EOF まで読み、末尾の {@code STDERR_TAIL_BYTES} バイトだけを持つ。
     *
     * <p>最後まで読み続けるのは、読まないとパイプが詰まり、ffmpeg が止まって読み込みも進まなくなるため。
     * 末尾だけ持つのは、壊れた入力でエラーが続いてもメモリを使い過ぎないためで、失敗の理由はふつう最後の行にある。
     */
    private static final class StderrTail implements Runnable {

        private final InputStream in;
        private final byte[] tail = new byte[STDERR_TAIL_BYTES];
        private int length;

        StderrTail(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            try (InputStream stream = in) {
                int n;
                while ((n = stream.read(buffer)) >= 0) {
                    append(buffer, n);
                }
            } catch (IOException e) {
                // ffmpeg を止めるとパイプが閉じられて読めなくなる。それまでに読んだ分で足りるので、ここでは何もしない
            }
        }

        /** 読んだ分を末尾に足し、{@code STDERR_TAIL_BYTES} を超えた分を先頭から捨てる（{@code n} は配列の長さ以下）。 */
        private synchronized void append(byte[] buffer, int n) {
            int keep = Math.min(length, tail.length - n);
            System.arraycopy(tail, length - keep, tail, 0, keep);
            System.arraycopy(buffer, 0, tail, keep, n);
            length = keep + n;
        }

        /** 読めた分を 1 行にして返す。改行は「 / 」にする（ログの行と実行記録の理由を 1 行に収めるため）。 */
        synchronized String text() {
            return new String(tail, 0, length, StandardCharsets.UTF_8).strip().replaceAll("\\s*\\R\\s*", " / ");
        }
    }
}
