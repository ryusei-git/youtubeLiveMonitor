package com.example.monitor.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 認証前に判定することで、制限中はパスワード照合もDB問い合わせも行わせない。 */
@RequiredArgsConstructor
public class LoginAttemptFilter extends OncePerRequestFilter {
    private final LoginAttemptLimiter limiter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/auth/login".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getParameter("username");
        String username = supplied == null ? "" : supplied.trim();
        // X-Forwarded-Forは利用者が偽装できるため、アプリへ接続した相手のアドレスを使う。
        LoginAttemptLimiter.Attempt attempt = limiter.begin(username, request.getRemoteAddr());
        if (!attempt.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(attempt.retryAfterSeconds()));
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("text/html;charset=UTF-8");
            // 管理者用の画面から来たなら管理者用のログイン画面へ戻す
            String loginPage = PortalAwareAuthenticationProvider.isAdminPortal(request) ? "/admin-login.html" : "/login.html";
            response.getWriter().write("""
                    <!doctype html><html lang="ja"><head><meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>ログインを一時制限中 - Live Monitor</title>
                    <link rel="stylesheet" href="/css/style.css"></head><body><main class="shell">
                    <h1>ログインを一時制限しています</h1>
                    <p>ログインの試行が上限に達しました。時間をおいて、もう一度お試しください。</p>
                    <p><a href="%s">ログイン画面へ戻る</a></p>
                    </main></body></html>
                    """.formatted(loginPage));
            return;
        }
        boolean successful = false;
        try {
            chain.doFilter(request, response);
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            successful = authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof AuthenticatedAppUser
                    && username.equals(authentication.getName());
        } finally {
            limiter.finish(attempt, successful);
        }
    }
}
