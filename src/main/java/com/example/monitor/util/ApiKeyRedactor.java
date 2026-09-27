package com.example.monitor.util;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Google API の失敗を、API キーを含まない形でログに書くための部品（Issue #495）。
 *
 * <p>Google の API ライブラリの例外は、本文にリクエストの URL（{@code ?key=<API キー>} 付き）を含む。
 * 例外をそのままログへ渡すとキーがログファイルに残り、管理画面のログの画面からも見えてしまう
 * （実際に本番のログに残っていた）。そこで例外の本体は渡さず、この説明だけを書く。
 */
public final class ApiKeyRedactor {

    /** URL の {@code key=} の値。区切り（{@code &}・空白・引用符）までを伏せる。 */
    private static final Pattern KEY = Pattern.compile("key=[^&\\s\"]+");

    /** ユーティリティクラスのためインスタンス化させない。 */
    private ApiKeyRedactor() {
    }

    /**
     * 文字列中の {@code key=...} を {@code key=***} に置き換える。
     *
     * @param text 伏せたい文字列。{@code null} 可
     * @return 伏せた文字列。{@code null} なら {@code null}
     */
    public static String redact(String text) {
        return text == null ? null : KEY.matcher(text).replaceAll("key=***");
    }

    /**
     * ログに書く失敗の説明。API の失敗なら HTTP の状態・{@code reason}・{@code message}、
     * それ以外なら例外の型と本文を、どちらも伏せ字に通して返す。
     *
     * <p>API の失敗で例外の本文（{@code getMessage()}）を使わないのは、そこにキー付きの URL が
     * そのまま入っているため。伏せ字は念のための二重の守り。
     *
     * @param e 呼び出しの失敗
     * @return キーを含まない説明
     */
    public static String describe(IOException e) {
        if (e instanceof GoogleJsonResponseException json) {
            GoogleJsonError details = json.getDetails();
            String reason = details != null && details.getErrors() != null && !details.getErrors().isEmpty()
                    ? details.getErrors().get(0).getReason() : null;
            String message = details != null ? details.getMessage() : null;
            return redact("HTTP " + json.getStatusCode() + " " + reason + " " + message);
        }
        return e.getClass().getSimpleName() + " " + redact(e.getMessage());
    }
}
