package com.example.monitor.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * 圧縮されて届いた HTTP 応答の本文を文字列に戻す処理。
 *
 * <p>{@code java.net.http.HttpClient} は {@code Accept-Encoding} を自分からは付けず、
 * 圧縮された応答を自動で展開もしない。そのため gzip を頼んだ側が自分で戻す必要がある。
 * YouTube の {@code /live} ページは gzip で約 4 分の 1（1.27MB → 0.31MB）になり、
 * 巡回のたびに全チャンネル分を受け取るので効果が大きい。
 */
public final class HttpResponseBodies {
    private HttpResponseBodies() {}

    /**
     * 応答本文を UTF-8 の文字列にする。{@code Content-Encoding: gzip} なら展開してから戻す。
     *
     * <p>展開に失敗したら例外をそのまま投げる。空文字などで握りつぶすと、呼び出し側が
     * 「配信していない」と誤判定する（「判定できなかった」として扱わせるため）。
     *
     * @param response 本文をバイト列で受け取った応答
     * @return 本文の文字列
     * @throws IOException gzip の展開に失敗したとき
     */
    public static String decodeUtf8(HttpResponse<byte[]> response) throws IOException {
        byte[] bytes = response.body();
        boolean gzip = response.headers().firstValue("Content-Encoding")
                .map("gzip"::equalsIgnoreCase)
                .orElse(false);
        if (gzip) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = in.readAllBytes();
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
