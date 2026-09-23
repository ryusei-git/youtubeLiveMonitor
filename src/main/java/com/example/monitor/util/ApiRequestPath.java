package com.example.monitor.util;

import jakarta.servlet.http.HttpServletRequest;

/** Servletマッピングに左右されず、認証関連の応答形式を同じAPI判定で選ぶ。 */
public final class ApiRequestPath {
    private ApiRequestPath() { }

    /**
     * コンテキストパスを除いたAPI接頭辞を照合する。
     * @param request 判定対象
     * @return APIへの要求か
     */
    public static boolean matches(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }
}
