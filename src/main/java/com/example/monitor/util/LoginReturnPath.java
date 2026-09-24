package com.example.monitor.util;

import java.net.URI;
import java.util.Set;

/** 再ログインの復帰先を画面だけに限定し、外部転送とAPIへの変更操作再送を防ぐ。 */
public final class LoginReturnPath {
    private static final Set<String> USER_PAGES = Set.of("/videos.html", "/my-channels.html", "/my-recordings.html");
    private static final Set<String> ADMIN_PAGES = Set.of("/", "/index.html", "/channels.html",
            "/recordings.html", "/player.html", "/notifications.html", "/users.html",
            "/invitations.html", "/logs.html", "/tables.html", "/playground.html", "/audit.html");

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
                    && (USER_PAGES.contains(path) || admin && ADMIN_PAGES.contains(path)) ? value : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
