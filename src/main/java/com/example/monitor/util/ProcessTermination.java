package com.example.monitor.util;

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
}
