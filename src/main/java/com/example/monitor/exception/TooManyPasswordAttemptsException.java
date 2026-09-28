package com.example.monitor.exception;

/**
 * パスワード変更で今のパスワードの照合に続けて失敗し、一時的に受け付けないときに発生する。
 *
 * <p>利用者の誤りでもサーバーの故障でもなく「時間がたてば戻る」制限なので、
 * 429 Too Many Requests に割り当てる。
 *
 * <p>いつ戻るかを {@code Retry-After} で機械的に伝えるため、再試行までの秒数を持つ
 * （ログインの試行制限と同じ）。
 */
public class TooManyPasswordAttemptsException extends RuntimeException {

    /** 再試行できるようになるまでの秒数。 */
    private final long retryAfterSeconds;

    /**
     * 再試行までの秒数を指定して例外を生成する。
     *
     * @param retryAfterSeconds 再試行できるようになるまでの秒数
     */
    public TooManyPasswordAttemptsException(long retryAfterSeconds) {
        super("パスワード変更の試行が上限に達しました。時間をおいて、もう一度お試しください。");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 再試行できるようになるまでの秒数を返す。{@code Retry-After} に載せる。
     *
     * @return 再試行までの秒数
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
