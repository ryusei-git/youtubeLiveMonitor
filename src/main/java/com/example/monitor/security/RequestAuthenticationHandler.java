package com.example.monitor.security;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.service.AuditLogger;
import com.example.monitor.util.ApiRequestPath;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import java.io.IOException;

/** APIへログイン画面のHTMLを返さず、セッション切れと権限不足を区別する。 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
public class RequestAuthenticationHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuditLogger auditLogger;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException, ServletException {
        if (ApiRequestPath.matches(request)) {
            writeError(response, 401, "ログインし直してください");
        } else {
            new LoginUrlAuthenticationEntryPoint("/login.html").commence(request, response, exception);
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        if (ApiRequestPath.matches(request)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean loggedIn = authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken);
            // 401（未ログイン）は記録しない。ログインしていない相手はここでしか操作を追えず、
            // 誰の操作かも特定できないまま監査ログが埋もれるだけになるため（Issue #49）。
            if (loggedIn) {
                recordAccessDenied(request, authentication);
            }
            // セッション切れのPOSTはCSRF検証が先に失敗するため、ここでも未認証を判定する。
            writeError(response, loggedIn ? 403 : 401,
                    loggedIn ? "操作が許可されていません。権限を確認し、必要なら画面を更新してください" : "ログインし直してください");
        } else {
            new AccessDeniedHandlerImpl().handle(request, response, exception);
        }
    }

    /**
     * 403（ログイン済みでの権限不足）を監査ログへ記録する。
     *
     * <p>{@link AuthenticatedAppUser} が保持する利用者IDをそのまま使う。
     * {@link RoleBasedAuthenticationSuccessHandler} 等と違いここではリポジトリを持たないため、
     * 名前からIDを引き直さず、認証情報に既に載っている値を使う
     * （{@link AuditLogoutHandler} と同じ考え方）。
     *
     * @param request        権限不足で拒否されたリクエスト
     * @param authentication ログイン済みの認証情報
     */
    private void recordAccessDenied(HttpServletRequest request, Authentication authentication) {
        Long userId = authentication.getPrincipal() instanceof AuthenticatedAppUser user ? user.getUserId() : null;
        auditLogger.record(AuditAction.ACCESS_DENIED, AuditOutcome.FAILURE, userId, authentication.getName(),
                request.getRemoteAddr(), "PATH", request.getRequestURI(), "method=" + request.getMethod());
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
