package com.example.monitor.util;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * 起動済みの外部プロセスを強制終了し、実際に終了したことを確認する。
 *
 * <h2>なぜ切り出したのか</h2>
 * 「{@code destroyForcibly()} を呼んだだけで戻ってはならない」という同じ後始末が、
 * {@code ExternalCommandRunner}（タイムアウト・割り込み時の後始末）と、
 * {@code StreamRecorder}/{@code VideoDownloadService}（プロセス起動後の録画履歴登録に
 * 失敗した際、起動済みプロセスを止める）の両方で必要になった。実際に OS 上で
 * プロセスが終了したことを確認せずに次の処理（排他の解放・後続の起動）へ進むと、
 * まだ出力先に書き込んでいるプロセスと次の起動が重なる恐れがある。
 * 複数クラスで使う処理のため、状態を持たない独立クラスとして {@code util} に切り出した。
 *
 * <h2>割り込み状態の復元は呼び出し側に委ねる</h2>
 * この処理単体で完結する呼び出し元は戻り値を見てすぐに割り込み状態を復元してよいが、
 * {@code ExternalCommandRunner} のようにこの後さらに別の割り込み可能な後始末
 * （読み取りスレッドの合流）を続ける呼び出し元では、ここで即座に復元してしまうと
 * 直後の待機が「既に割り込み済み」として即座に例外を投げ、その後始末が実質できなくなる。
 * そのためここでは復元をせず、割り込みが起きたことだけを戻り値で伝える。
 */
public final class ProcessTermination {

    private ProcessTermination() {
    }

    /**
     * プロセスを強制終了し、実際に終了するまで待つ。
     *
     * @param process 対象のプロセス
     * @return 終了を待っている間に割り込まれた場合 {@code true}
     *         （割り込み状態の復元要否は呼び出し側が判断する。クラスの JavaDoc 参照）
     */
    public static boolean destroyForciblyAndAwait(Process process) {
        process.destroyForcibly();
        try {
            process.waitFor();
            return false;
        } catch (InterruptedException e) {
            return true;
        }
    }

    /**
     * プロセスとその子孫を SIGTERM で止め、{@code grace} 待っても残ったものを SIGKILL で止めて、
     * すべて終わるまで待つ。
     *
     * <p><b>子孫は止める前に集める。</b>親が先に死ぬと子は別の親へ付け替わり、
     * {@link ProcessHandle#descendants()} でたどれなくなる。Twitch の録画は yt-dlp の子の ffmpeg が
     * 出力を書いており、yt-dlp だけを止めると ffmpeg が孤児として書き続ける（本番で確認した）。
     * まず SIGTERM にするのは、ffmpeg が出力を閉じてから終わり、ファイルの末尾が壊れにくいため。
     *
     * <p><b>SIGINT は使わない。</b>{@link Process}/{@link ProcessHandle} からは送れず、外部の {@code kill} を
     * 起動することになる。また、固まって進んでいないプロセスが SIGINT で結合まで進むことも期待できない。
     * 途中のファイルを再生できる形にするのは {@code RecordingSalvager} が行う。
     *
     * <p>{@link ProcessHandle} を受け取るのは、{@link Process} を持たない呼び出し元
     * （再起動後に残ったプロセスを OS から引いた場合）でも使えるようにするため。
     *
     * @param root  止めるプロセス
     * @param grace SIGTERM の後、SIGKILL に切り替えるまで待つ時間
     * @return 終了を待っている間に割り込まれた場合 {@code true}（残りには SIGKILL を送り済み。
     *         割り込み状態の復元要否は呼び出し側が判断する。クラスの JavaDoc 参照）
     */
    public static boolean terminateTreeAndAwait(ProcessHandle root, Duration grace) {
        List<ProcessHandle> tree = Stream.concat(root.descendants(), Stream.of(root)).toList();
        tree.forEach(ProcessHandle::destroy);
        try {
            long deadline = System.nanoTime() + grace.toNanos();
            for (ProcessHandle h : tree) {
                long remainingMillis = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                try {
                    h.onExit().get(remainingMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException | ExecutionException e) {
                    // 猶予が尽きたものは下の SIGKILL で止める
                }
            }
            for (ProcessHandle h : tree) {
                if (h.isAlive()) {
                    h.destroyForcibly();
                }
            }
            for (ProcessHandle h : tree) {
                try {
                    h.onExit().get();
                } catch (ExecutionException e) {
                    // onExit は例外で完了しない。念のため次へ進む
                }
            }
            return false;
        } catch (InterruptedException e) {
            tree.forEach(ProcessHandle::destroyForcibly);
            return true;
        }
    }

}
