package com.example.monitor.sound;

import com.example.monitor.util.ProcessTermination;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Path;
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
 * <p>標準エラーは JVM の標準エラーへそのまま流す（{@code -loglevel error} なので、ふつうは何も出ない）。
 * 読まないパイプにすると、壊れた入力でエラーが続いたときにパイプが詰まり、ffmpeg が止まって読み込みも進まなくなる。
 * CLI では ffmpeg の失敗の理由が画面に出る。
 */
public final class PcmDecoder {

    private PcmDecoder() {
    }

    /**
     * ffmpeg を起動し、デコードした PCM を読むストリームを返す。
     *
     * <p>{@code -ss}・{@code -to} は入力の側に付ける（入力の時刻で指定し、開始位置までは読み飛ばす）。
     * 返したストリームは、最後まで読むと ffmpeg の終了コードを確かめ、0 以外なら {@link IOException} を投げる
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
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        return new DecodedStream(process, file);
    }

    /** ffmpeg に渡す秒数。{@code Double.toString} は大きい値を指数表記（{@code 1.0E7}）にし、ffmpeg が読めないため。 */
    private static String seconds(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** ffmpeg の標準出力。最後まで読んだら終了コードを確かめ、閉じたら ffmpeg を止める。 */
    private static final class DecodedStream extends FilterInputStream {

        private final Process process;
        private final Path file;

        DecodedStream(Process process, Path file) {
            super(process.getInputStream());
            this.process = process;
            this.file = file;
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
                throw new IOException("ffmpeg が終了コード " + exitCode + " で失敗しました: " + file);
            }
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
}
