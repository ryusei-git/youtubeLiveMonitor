package com.example.monitor.exception;

/**
 * 監視サイクルが既に実行中のときに、手動チェックを重ねて要求した場合に発生する。
 *
 * <p>利用者の入力が誤っているわけでも、サーバーが壊れているわけでもなく、
 * 「少し待てば解消する」性質の衝突なので、専用の例外として 409 Conflict に割り当てている。
 */
public class MonitoringInProgressException extends RuntimeException {

    /** 既定のメッセージで例外を生成する。 */
    public MonitoringInProgressException() {
        super("監視サイクルが既に実行中です。完了までお待ちください");
    }
}
