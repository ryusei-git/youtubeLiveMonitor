package com.example.monitor.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * URL が指定したドメインのものかを、ホスト名を見て判定する共通処理。
 *
 * <h2>なぜ {@code contains} で済ませないのか</h2>
 * 「{@code youtube.com} を含むか」で判定すると、
 * {@code https://example.com/youtube.com/watch?v=x} のような<b>別サイトの URL まで
 * YouTube のものと誤認する</b>。誤認したまま {@code yt-dlp} に渡すと、
 * 利用者には「対応していない URL です」ではなく「動画情報を取得できませんでした」という
 * 見当違いの理由が返り、原因にたどり着けない。
 *
 * <p>ホスト名で判定すれば {@code www.} や {@code m.}（モバイル版）といった
 * サブドメインの違いも {@code endsWith} で自然に吸収できる。
 *
 * <p>各 {@link com.example.monitor.platform.StreamPlatform} の実装が
 * 「この URL は自分の担当か」を判断するのに使う。プラットフォームが増えるたびに
 * 同じ判定が要るため、独立クラスに切り出している。
 */
public final class UrlHostMatcher {

    /** スキームが省略された入力（{@code youtube.com/watch?v=x}）を URI として解釈するために補う接頭辞。 */
    private static final String FALLBACK_SCHEME = "https://";

    private UrlHostMatcher() {
    }

    /**
     * URL のホストが、指定したドメインのいずれかに一致するかを判定する。
     *
     * <p>サブドメインは一致とみなす（{@code www.youtube.com} は {@code youtube.com} に一致）。
     *
     * @param url     判定する URL。スキームは省略されていてもよい
     * @param domains 一致とみなすドメイン（{@code youtube.com} のように小文字で渡す）
     * @return いずれかのドメインに一致すれば {@code true}。URL として解釈できない場合は {@code false}
     */
    public static boolean matchesAnyDomain(String url, String... domains) {
        String host = extractHost(url);
        if (host == null) {
            return false;
        }
        for (String domain : domains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * URL からホスト名を取り出す。
     *
     * <p>利用者はアドレスバーからコピーするとは限らず、{@code youtube.com/watch?v=x} のように
     * スキームを落とした形を入力することがある。その場合 {@link URI} はホストを認識しない
     * （全体がパスとして扱われる）ため、{@code https://} を補ってもう一度解釈する。
     *
     * @param url 対象の URL
     * @return 小文字化したホスト名。取り出せなければ {@code null}
     */
    private static String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        String host = hostOf(trimmed);
        if (host == null) {
            host = hostOf(FALLBACK_SCHEME + trimmed);
        }
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    /**
     * 文字列を URI として解釈してホスト名を返す。
     *
     * @param candidate 解釈する文字列
     * @return ホスト名。URI として解釈できない場合や、ホストを持たない場合は {@code null}
     */
    private static String hostOf(String candidate) {
        try {
            return new URI(candidate).getHost();
        } catch (URISyntaxException e) {
            // 解釈できない文字列は「どのプラットフォームでもない」として扱えばよく、例外にする必要はない
            return null;
        }
    }
}
