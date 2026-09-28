package com.example.monitor.exception;

/**
 * YouTube の検索（{@code search.list}）や視聴画面の動画の詳細が本日の上限に達したときに発生する。
 *
 * <p>利用者の誤りでもサーバーの故障でもなく「日付が変われば戻る」制限なので、
 * 429 Too Many Requests に割り当て、いつ戻るかを文言に含める
 * （米国太平洋時間の 0 時＝日本の 16 時（夏時間）か 17 時）。
 */
public class SearchQuotaExceededException extends RuntimeException {

    /** 既定のメッセージで例外を生成する。 */
    public SearchQuotaExceededException() {
        super("本日の検索回数の上限に達しました（16〜17 時ごろに戻ります）");
    }

    /**
     * 文言を指定して例外を生成する（検索以外の上限で、何の回数が尽きたかを伝えるため）。
     *
     * @param message 利用者に返す文言
     */
    public SearchQuotaExceededException(String message) {
        super(message);
    }
}
