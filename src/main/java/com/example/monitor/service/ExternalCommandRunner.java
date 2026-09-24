package com.example.monitor.service;

import com.example.monitor.util.ProcessTermination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 短時間で終わる外部コマンドを実行し、標準出力を受け取る。
 *
 * <p>{@code ffprobe} / {@code ffmpeg} のような「起動して、出力を読み切って、終了コードを見る」
 * という定型処理が {@link VideoMetadataExtractor} と {@link RecordingSalvager} の両方で
 * 必要になったため、独立させた。タイムアウトや異常終了の扱いを片方だけ直す、という
 * ずれを防ぐのが目的。
 *
 * <p><b>録画本体（{@code yt-dlp}）の起動には使わない。</b>
 * あちらは数時間動き続けるうえ、出力を読みながら完了を待つ流れが
 * {@link StreamRecorder#awaitCompletion} に組み込まれているため、性質が違う。
 *
 * <h2>なぜ「標準出力を読み切ってから待つ」ではいけないのか（実際に発生した）</h2>
 * 以前は {@code readLine()} で標準出力を EOF まで読み切ったあとで初めて
 * {@code waitFor(timeoutSeconds, ...)} を呼んでいた。{@code readLine()} はパイプの EOF
 * （＝プロセス終了）まで戻らないため、標準出力を開いたまま応答しなくなったプロセスに対しては
 * <b>タイムアウトの行に構造上到達しなかった</b>。{@code sleep 3} をタイムアウト1秒で実行したところ、
 * 実際に 3.01 秒待った末に「成功」扱いで値ありの {@link Optional} が返ることを確認している。
 *
 * <p>このクラスは {@link RecordingReconciler} 経由で監視の巡回サイクルから呼ばれるため、
 * 外部コマンドが応答しなくなると巡回・録画・通知がまとめて止まる。読み取りと終了待ちを
 * 並行させ、その合計に対して 1 つの期限を適用することで直している（{@link #run(List, String, long)} 参照）。
 * 読み取りを完了待ちより先に止めてしまう（つまり読み取りを並行させない）実装も、
 * OS のパイプバッファが一杯になった時点でプロセス側の書き込みがブロックし、
 * 別の形で両者とも進まなくなるため避けている。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalCommandRunner {

    /**
     * 溜める出力の上限（文字数。出力はほぼ ASCII なので約 1MB）。超えた分は読んで捨てる。
     *
     * <p>呼び出し側が使う出力は {@code ffprobe} の数値や {@code yt-dlp} の 1 行だけで、数百文字に収まる。
     * 一方で {@code ffmpeg} は、壊れた入力に対してパケットごとにメッセージを出しうる
     * （{@code -v error} で警告は止まるが、エラーは出る）。上限が無いと、それを巡回のたびに
     * 丸ごとメモリに抱える（#184）。
     *
     * <p>上限は行単位で見る（行の途中では切らない）。1 行が極端に長いと {@code readLine()} がその行を
     * 丸ごと持つが、ここで呼ぶコマンドの出力は短い行ばかりなので行単位で済ませている。
     */
    private static final int MAX_OUTPUT_CHARS = 1024 * 1024;

    /** 上限を超えて捨てた出力があったときに、出力の末尾に付ける印。 */
    private static final String TRUNCATION_MARKER = "（以下省略）";

    private final ProcessLauncher processLauncher;

    /**
     * 外部コマンドを実行し、標準出力を返す。
     *
     * <p>失敗はすべて {@link Optional#empty()} に畳む。呼び出し側はいずれの失敗でも
     * 「その付加機能を諦めて先へ進む」以外の対応を取らないため。
     *
     * <p><b>待ち時間の上限は呼び出し側が決める。</b>これらの処理は監視の巡回サイクルから
     * 呼ばれるため、長すぎる上限は監視そのものを止める。一方で短すぎると、
     * 数十GBの録画ファイルの詰め替えのような「正当に時間がかかる処理」を打ち切ってしまう。
     * 適切な値は用途ごとに違うので既定値を持たせていない。
     *
     * @param command        実行するコマンドと引数
     * @param contextFile    ログに出す対象ファイル（原因の切り分け用）
     * @param timeoutSeconds 応答待ちの上限（秒）。超えたら強制終了する
     * @return 標準出力（上限を超えた分は省略。{@code MAX_OUTPUT_CHARS} 参照）。
     *         起動失敗・タイムアウト・異常終了の場合は {@link Optional#empty()}
     */
    public Optional<String> run(List<String> command, Path contextFile, long timeoutSeconds) {
        return run(command, contextFile.toString(), timeoutSeconds);
    }

    /**
     * 外部コマンドを実行し、標準出力を返す。対象がファイルではない場合はこちらを使う。
     *
     * <p>{@code yt-dlp} に動画の URL を渡してメタデータだけ取得する
     * （{@link VideoSourceProbe}）ように、<b>対象がローカルのファイルとは限らない</b>ため
     * 用意している。{@code contextFile} を無理に {@code Path} にすると、URL を
     * ファイルパスとして扱う不自然な変換が呼び出し側に生まれる。
     *
     * @param command        実行するコマンドと引数
     * @param context        ログに出す処理対象（原因の切り分け用）
     * @param timeoutSeconds 応答待ちの上限（秒）。超えたら強制終了する
     * @return 標準出力（上限を超えた分は省略。{@code MAX_OUTPUT_CHARS} 参照）。
     *         起動失敗・タイムアウト・異常終了の場合は {@link Optional#empty()}
     */
    public Optional<String> run(List<String> command, String context, long timeoutSeconds) {
        Process process;
        try {
            process = processLauncher.launch(command);
        } catch (IOException e) {
            log.warn("{} を起動できませんでした（インストールされていない可能性があります）: 対象={}",
                    command.get(0), context);
            return Optional.empty();
        }

        // 標準出力の読み取りは完了待ちと並行して進める理由はクラスの JavaDoc を参照。
        // ここで先に読み取りスレッドを立ち上げてから waitFor に入ることで、
        // 「大量出力でパイプが詰まる」→「プロセスが書き込みでブロックする」→
        // 「waitFor も readLine も両方進まない」という詰みを避けている。
        OutputReadTask readTask = new OutputReadTask(process);
        Thread readerThread = Thread.ofVirtual().name("external-command-reader").start(readTask);

        // 「読み取り」と「終了待ち」の合計にこの期限を適用する（片方だけに適用しても、
        // もう片方が無期限に残っていれば全体としては直らない）。
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        boolean exited;
        try {
            exited = process.waitFor(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            // 割り込み状態を戻すのは後始末の後。先に戻すと、直後の terminateAndAwait 内の
            // waitFor/join が「既に割り込み済み」として即座に投げ返してしまい、
            // destroyForcibly() 後の終了確認が実質できなくなる。
            terminateAndAwait(process, readerThread);
            Thread.currentThread().interrupt();
            log.warn("{} の完了待ちが中断されました: 対象={}", command.get(0), context);
            return Optional.empty();
        }

        if (!exited) {
            terminateAndAwait(process, readerThread);
            log.warn("{} が時間内に終わらなかったため打ち切りました: 対象={}", command.get(0), context);
            return Optional.empty();
        }

        // プロセスは既に終了している。標準出力はまもなく EOF に達するはずだが、
        // 万一読み取りが進まなくなっていた場合に無期限に待たないよう、同じ期限を使い切る。
        boolean readerFinished;
        try {
            readerFinished = joinWithinDeadline(readerThread, deadlineNanos);
        } catch (InterruptedException e) {
            // 割り込み状態を戻すのは後始末の後（理由は上の waitFor の catch と同じ）
            terminateAndAwait(process, readerThread);
            Thread.currentThread().interrupt();
            log.warn("{} の出力読み取り待ちが中断されました: 対象={}", command.get(0), context);
            return Optional.empty();
        }

        if (!readerFinished) {
            terminateAndAwait(process, readerThread);
            log.warn("{} の出力読み取りが時間内に終わりませんでした: 対象={}", command.get(0), context);
            return Optional.empty();
        }

        if (readTask.failure != null) {
            log.warn("{} の出力読み取りに失敗しました: 対象={}", command.get(0), context, readTask.failure);
            terminateAndAwait(process, readerThread);
            return Optional.empty();
        }

        if (process.exitValue() != 0) {
            log.warn("{} が異常終了しました: 対象={}, exitCode={}",
                    command.get(0), context, process.exitValue());
            return Optional.empty();
        }
        return Optional.of(readTask.output);
    }

    /**
     * 期限までの残り時間をナノ秒で返す。負にはしない。
     *
     * <p>{@link Process#waitFor(long, TimeUnit)} は 0 以下を渡しても
     * 「即座に確認するだけ」として扱われ無期限待ちにはならないため、そのまま渡してよい
     * （{@link #joinWithinDeadline} で使う {@link Thread#join(long)} は 0 の扱いが逆
     * ＝無期限待ちになるため、そちらは呼び出し側で別に処理している）。
     *
     * @param deadlineNanos {@link System#nanoTime()} 基準の期限
     * @return 残り時間（ナノ秒）。既に過ぎていれば 0
     */
    private static long remainingNanos(long deadlineNanos) {
        return Math.max(deadlineNanos - System.nanoTime(), 0);
    }

    /**
     * 読み取りスレッドが期限内に終わるのを待つ。
     *
     * <p>{@link Thread#join(long)} は引数に {@code 0} を渡すと「無期限に待つ」という
     * {@link Process#waitFor(long, TimeUnit)} とは逆の意味になる。期限を使い切っていた場合に
     * 誤って無期限待ちへ落ちないよう、残り時間が 0 以下なら join を呼ばず現在の生死だけを見る。
     *
     * @param readerThread  読み取りを行っているスレッド
     * @param deadlineNanos {@link System#nanoTime()} 基準の期限
     * @return 期限内にスレッドが終了していれば {@code true}
     */
    private static boolean joinWithinDeadline(Thread readerThread, long deadlineNanos) throws InterruptedException {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadlineNanos));
        if (remainingMillis > 0) {
            readerThread.join(remainingMillis);
        }
        return !readerThread.isAlive();
    }

    /**
     * プロセスを強制終了し、実際に終了したことを確認したうえで読み取りスレッドの後始末をする。
     *
     * <p>プロセスの強制終了と終了確認そのものは {@link ProcessTermination#destroyForciblyAndAwait}
     * に切り出している（{@code StreamRecorder}/{@code VideoDownloadService} の録画履歴登録失敗時の
     * 後始末と同じ処理のため）。<b>{@code destroyForcibly()} を呼んだだけでは終わりにしない</b>
     * 理由はそちらの JavaDoc を参照。
     *
     * <p>読み取りスレッドは、プロセスの標準出力が閉じれば {@code readLine()} が EOF を返して
     * 自然に終わるはずなので、まず短時間の合流だけを試みる。それでも終わらない場合は
     * {@link Thread#interrupt()} を呼んでおく（ブロッキング I/O は中断に反応しないことが多いが、
     * 少なくとも意図は示しておく。スレッド自体は仮想スレッドなので、居残っても
     * OS スレッドを専有し続けるわけではない）。
     *
     * <p>途中で割り込まれても、割り込み状態を戻すのは 2 つの後始末を終えた最後にする。
     * 先に戻してしまうと、まだ残っている {@code join()} が
     * 「既に割り込み済み」として即座に投げ返し、後始末が実質できないまま終わってしまう
     * （{@link ProcessTermination} が割り込み状態の復元を呼び出し側に委ねているのはこのため）。
     *
     * @param process      対象のプロセス
     * @param readerThread 標準出力を読み取っているスレッド
     */
    private void terminateAndAwait(Process process, Thread readerThread) {
        boolean interrupted = ProcessTermination.destroyForciblyAndAwait(process);

        try {
            readerThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (readerThread.isAlive()) {
            readerThread.interrupt();
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 標準出力を読み切るまでの処理を、完了待ちと並行して走らせるためのタスク。
     *
     * <p>結果を戻り値ではなくフィールドに保持しているのは、このタスクを実行するスレッドを
     * {@link Thread#join} した後に読み出すため。Java のメモリモデル上、{@code join} の完了は
     * 対象スレッド内の書き込みと呼び出し元のその後の読み出しの間に happens-before 関係を作るので、
     * {@code volatile} 等を使わなくても join 後の読み出しは安全（{@link StreamRecorder} が
     * 仮想スレッドの完了待ちに使っているのと同じ考え方）。
     */
    private static final class OutputReadTask implements Runnable {
        private final Process process;
        private String output = "";
        private IOException failure;

        OutputReadTask(Process process) {
            this.process = process;
        }

        @Override
        public void run() {
            StringBuilder builder = new StringBuilder();
            boolean truncated = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 上限を超えた後も EOF まで読み続けて捨てる。読むのをやめるとパイプが詰まり、
                    // 子プロセスが書き込みで止まって打ち切りになる（クラスの JavaDoc 参照）
                    truncated |= builder.length() + line.length() + 1 > MAX_OUTPUT_CHARS;
                    if (!truncated) {
                        builder.append(line).append('\n');
                    }
                }
                if (truncated) {
                    builder.append(TRUNCATION_MARKER);
                }
                output = builder.toString();
            } catch (IOException e) {
                failure = e;
            }
        }
    }
}
