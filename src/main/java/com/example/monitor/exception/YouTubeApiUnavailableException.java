package com.example.monitor.exception;

/**
 * YouTube Data API を使えないとき（API キーが未設定・API が失敗した）に発生する。
 *
 * <p>利用者の誤りではなく、待つか管理者が直せば戻る状態なので 503 Service Unavailable に割り当てる。
 * 上限に達した場合は {@link SearchQuotaExceededException}（429）を使い、こちらとは分ける
 * （「いつ戻るか」が決まっているかどうかが違うため）。
 */
public class YouTubeApiUnavailableException extends RuntimeException {

    /**
     * @param message 利用者に見せる理由（API キーやエラーの詳細を含めない）
     */
    public YouTubeApiUnavailableException(String message) {
        super(message);
    }
}
