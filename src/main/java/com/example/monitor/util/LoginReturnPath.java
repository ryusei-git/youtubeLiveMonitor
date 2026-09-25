package com.example.monitor.util;

import java.net.URI;
import java.util.Set;

/**
 * 再ログインの復帰先を画面だけに限定し、外部転送とAPIへの変更操作再送を防ぐ。
 *
 * <p>利用者画面の 1 枚のページ（{@code /my} 配下、#146）は、画面と検索条件を URL で表すため
 * 決め打ちの一覧に載せられない。{@code /my} 配下は丸ごと許し、ほかの画面へ抜けられる
 * {@code .}・{@code ..} のセグメントだけを拒む。
 */
public final class LoginReturnPath {
    /** 利用者の旧画面の URL（{@code /videos.html} は管理者の動画一覧も兼ねる）。ブックマークから来た利用者が
     *  ログイン後にここへ戻り、そこから新しい画面へ移されるよう残す（#178）。 */
    private static final Set<String> USER_PAGES = Set.of("/videos.html", "/my-channels.html", "/my-recordings.html");
    /** 再生画面は管理者だけの画面（利用者は {@code /my/watch/<ID>}、#178）。利用者の復帰先に選ばせると 403 になる。 */
    private static final Set<String> ADMIN_PAGES = Set.of("/", "/index.html", "/channels.html",
            "/recordings.html", "/player.html", "/notifications.html", "/users.html",
            "/invitations.html", "/logs.html", "/tables.html", "/audit.html");

    private LoginReturnPath() { }

    /**
     * 未ログインで開かれた画面に合わせてログイン画面を選ぶために使う。
     * 管理者の画面から利用者用のログイン画面へ送ると、そこからは管理者がログインできないため。
     * @param path 開かれた画面のパス
     * @return 管理者専用の画面なら true
     */
    public static boolean isAdminPage(String path) {
        return ADMIN_PAGES.contains(path);
    }

    /**
     * 未ログインで開かれたパスを、ログイン後に戻る先として保存してよいかを決めるために使う。
     * 保存する時点ではまだ誰がログインするか分からないため、受け付ける範囲が広い管理者として照合する
     * （利用者・管理者どちらの画面も通る）。
     * @param path 開かれたパス
     * @return 利用者・管理者どちらかの画面（{@code /my} 配下を含む）なら true
     */
    public static boolean isPage(String path) {
        return validate(path, true) != null;
    }

    /**
     * パラメーターは利用者が自由に変更できるため、サーバーでも復帰可能な画面を照合する。
     * @param value 要求された復帰先
     * @param admin 管理者か
     * @return 安全な相対パス。不正・権限外ならnull
     */
    public static String validate(String value, boolean admin) {
        if (value == null || value.length() > 2048 || !value.startsWith("/")
                || value.startsWith("//") || value.contains("\\")
                || value.chars().anyMatch(Character::isISOControl)) return null;
        try {
            URI uri = URI.create(value);
            String path = uri.getRawPath();
            return uri.getRawAuthority() == null && uri.getScheme() == null
                    && (USER_PAGES.contains(path) || isMyPage(uri) || admin && ADMIN_PAGES.contains(path)) ? value : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code /my} 配下の画面かを見る。{@code /my/../tables.html} のように {@code /my} の外へ
     * 抜けるパスは拒む。{@code .} と {@code ..} は復号してから見る。ブラウザは {@code %2e} も
     * {@code .} として扱い、{@code /my/%2e%2e/tables.html} を {@code /tables.html} へ解決するため。
     * @param uri 要求された復帰先
     * @return {@code /my} 配下の画面なら true
     */
    private static boolean isMyPage(URI uri) {
        String path = uri.getRawPath();
        if (!path.equals("/my") && !path.startsWith("/my/")) return false;
        for (String segment : uri.getPath().split("/")) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }
}
