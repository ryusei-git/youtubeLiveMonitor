package com.example.monitor.exception;

/**
 * 監視を止めた起動（{@code monitor.scheduling.enabled=false}。確認用の起動）で、手動チェックを要求した場合に発生する。
 *
 * <p>以前は {@link MonitoringInProgressException} と区別せず「既に実行中です。完了までお待ちください」を返していた。
 * しかしこの状態は待っても解消しない（起動し直すまで続く）。確認用の起動で画面を確かめる人が
 * 「待てば実行できる」と誤解しないよう、理由を別の例外で伝える。
 *
 * <p>要求の形は正しく、サーバーの今の状態（監視を止めた起動）と合わないだけなので、
 * {@link MonitoringInProgressException} と同じ 409 Conflict に割り当てている。
 */
public class MonitoringDisabledException extends RuntimeException {

    /** 既定のメッセージで例外を生成する。 */
    public MonitoringDisabledException() {
        super("この起動は監視を止めているため（monitor.scheduling.enabled=false）、今すぐチェックは行いません");
    }
}
