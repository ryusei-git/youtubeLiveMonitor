package com.example.monitor.util;

import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 「今どのリクエストを処理しているか」を MDC に載せ、1 つの操作を複数のログにまたがって追えるようにする。
 *
 * <h2>なぜ相関 ID が要るのか</h2>
 * 監査ログ（DB）・アプリログ（ファイル）は目的が違うため別々に残している。
 * そのままでは<b>監査ログで不審な操作を見つけても、その操作中に何が起きたかを
 * アプリログから特定できない</b>。両方に同じ ID を載せておけば、片方で見つけた手がかりから
 * もう片方を引ける。
 *
 * <h2>前の値を退避してから戻す理由</h2>
 * {@link ChannelLogContext} と同じ。サーブレットコンテナは<b>スレッドを使い回す</b>ため、
 * 単に消し忘れると次のリクエストのログに前の ID が残り、<b>別人の操作を同一の操作として
 * 追ってしまう</b>。監査を目的にする以上、この取り違えは致命的になる。
 *
 * <h2>利用者名を MDC に持たせていない理由</h2>
 * 相関 ID を発行するフィルターは<b>認証処理より前に動く</b>（認証で弾かれたリクエストにも
 * ID を付けたいため）。その時点ではまだ誰か分からない。
 * 利用者名が要る場面（監査ログの記録）は認証後なので、そのときに
 * {@link #currentUsername()} で取得すればよい。
 */
public final class RequestContext {

    /** リクエストを識別する相関 ID の MDC キー。 */
    private static final String MDC_REQUEST_ID_KEY = "requestId";

    private RequestContext() {
    }

    /**
     * 新しい相関 ID を発行する。
     *
     * @return 発行した相関 ID
     */
    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 指定した相関 ID の範囲で処理を実行し、その結果を返す。
     *
     * @param requestId 相関 ID
     * @param action    実行する処理
     * @param <T>       処理の戻り値の型
     * @return 処理の戻り値
     */
    public static <T> T callWithRequestId(String requestId, Supplier<T> action) {
        String previous = MDC.get(MDC_REQUEST_ID_KEY);
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_REQUEST_ID_KEY);
            } else {
                MDC.put(MDC_REQUEST_ID_KEY, previous);
            }
        }
    }

    /**
     * 指定した相関 ID の範囲で処理を実行する。戻り値が要らない場合はこちらを使う。
     *
     * @param requestId 相関 ID
     * @param action    実行する処理
     */
    public static void runWithRequestId(String requestId, Runnable action) {
        callWithRequestId(requestId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 現在の相関 ID を返す。
     *
     * @return 相関 ID。リクエストの外（巡回処理など）では {@code null}
     */
    public static String currentRequestId() {
        return MDC.get(MDC_REQUEST_ID_KEY);
    }

    /**
     * 相関 ID を立て、直前の値を返す。<b>必ず {@link #restore(String)} と対で使うこと。</b>
     *
     * <p>{@link #callWithRequestId} が使えない場面（検査例外を投げる処理を挟むサーブレット
     * フィルターなど）のための口。呼び出し側で MDC のキー文字列を書かせないために用意している
     * ——キーが複製されると、片方だけ変えたときに<b>相関 ID が静かに載らなくなる</b>。
     *
     * @param requestId 立てる相関 ID
     * @return 直前の相関 ID。無ければ {@code null}
     */
    public static String put(String requestId) {
        String previous = MDC.get(MDC_REQUEST_ID_KEY);
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        return previous;
    }

    /**
     * {@link #put(String)} が返した直前の値に戻す。
     *
     * @param previous {@link #put(String)} の戻り値
     */
    public static void restore(String previous) {
        if (previous == null) {
            MDC.remove(MDC_REQUEST_ID_KEY);
        } else {
            MDC.put(MDC_REQUEST_ID_KEY, previous);
        }
    }

    /**
     * 現在ログインしている利用者名を返す。
     *
     * <p>未ログイン時に返る匿名の認証（{@code anonymousUser}）は、利用者ではないので
     * {@code null} に寄せる。監査ログ側で「誰の操作か不明」と「匿名利用者という名前の人物」を
     * 取り違えないようにするため。
     *
     * @return 利用者名。未認証の場合は {@code null}
     */
    public static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String name = authentication.getName();
        return "anonymousUser".equals(name) ? null : name;
    }
}
