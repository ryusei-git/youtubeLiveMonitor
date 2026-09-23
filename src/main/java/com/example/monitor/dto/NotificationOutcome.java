package com.example.monitor.dto;

/**
 * 通知 1 回の送信結果。
 *
 * <p>送信処理は例外を投げる代わりにこの型を返す。理由は、
 * 呼び出し側が「失敗しても処理を止めず、次回の監視サイクルに再送信を委ねる」という
 * 振る舞いを取るため。例外のまま伝播させると、1 チャンネルの失敗が
 * 監視ループ全体を巻き込みやすくなる。
 *
 * @param successful   送信が成功したか
 * @param errorMessage 失敗時の理由。成功時は {@code null}
 */
public record NotificationOutcome(boolean successful, String errorMessage) {

    /**
     * 成功を表す結果を生成する。
     *
     * @return 成功を表す結果
     */
    public static NotificationOutcome success() {
        return new NotificationOutcome(true, null);
    }

    /**
     * 失敗を表す結果を生成する。
     *
     * @param errorMessage 失敗した理由
     * @return 失敗を表す結果
     */
    public static NotificationOutcome failure(String errorMessage) {
        return new NotificationOutcome(false, errorMessage);
    }
}
