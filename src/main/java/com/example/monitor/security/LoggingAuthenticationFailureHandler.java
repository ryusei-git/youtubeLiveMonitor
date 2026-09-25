package com.example.monitor.security;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.service.AuditLogger;
import com.example.monitor.util.LoginReturnPath;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * ログイン失敗をアプリログと監査ログの両方に残したうえで、来た画面のログイン画面へ戻す。
 *
 * <p>失敗の記録が漏れると総当たり攻撃などの兆候に気づけなくなる
 * （{@code GlobalExceptionHandler} が利用者操作の失敗を必ず WARN で残しているのと同じ理由）。
 *
 * <h2>存在しない利用者名でも同じ扱いにする</h2>
 * {@link AuditLogger#recordAuthEvent} には常に {@code userId=null} を渡す。
 * 実在する利用者かどうかで記録の仕方を変えると、監査ログの内容そのものから
 * 利用者名の実在を推測できてしまう（{@code LoginAttemptLimiter} が実在・非実在を
 * 同じ応答にしているのと同じ考え方）。
 *
 * <h2>ログイン制限中は記録されない</h2>
 * {@code LoginAttemptFilter} が {@code UsernamePasswordAuthenticationFilter} より前段で
 * 制限中のリクエストを 429 で打ち切るため、このハンドラ自体が呼ばれない。
 * 総当たりが続いても監査ログの行数は際限なく増えない。
 */
@Component
@Profile("!cli")
@Slf4j
public class LoggingAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    /** 失敗時に戻す先。{@code userLogin.html} 側がこのクエリパラメータの有無でエラー表示を出す。 */
    private static final String FAILURE_URL = "/userLogin.html?error";

    /** 管理者用の画面から来たときに戻す先。 */
    private static final String ADMIN_FAILURE_URL = "/adminLogin.html?error";

    private final AuditLogger auditLogger;

    public LoggingAuthenticationFailureHandler(AuditLogger auditLogger) {
        super(FAILURE_URL);
        this.auditLogger = auditLogger;
    }

    /**
     * ログイン失敗を WARN で記録してから、来た画面（{@code portal}）のログイン画面へ戻す。
     *
     * @param request   リクエスト
     * @param response  レスポンス
     * @param exception 認証に失敗した理由
     * @throws IOException      リダイレクト処理で発生しうる例外
     * @throws ServletException 親クラスの処理で発生しうる例外
     */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        // パスワードそのものはログに残さない（usernameパラメータのみ参照する）
        String username = request.getParameter("username");
        log.warn("ログインに失敗しました: user={}, reason={}", username, exception.getMessage());
        // 存在しない利用者名でも同じ経路を通る。実在の有無を監査ログの有無から
        // 読み取れてしまわないよう、userId は常に null のまま記録する
        auditLogger.recordAuthEvent(AuditAction.LOGIN_FAILURE, AuditOutcome.FAILURE,
                null, username, request.getRemoteAddr(), exception.getMessage());
        String failureUrl = PortalAwareAuthenticationProvider.isAdminPortal(request) ? ADMIN_FAILURE_URL : FAILURE_URL;
        String returnTo = LoginReturnPath.validate(request.getParameter("returnTo"), true);
        if (returnTo != null) {
            failureUrl += "&returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
        }
        saveException(request, exception);
        getRedirectStrategy().sendRedirect(request, response, failureUrl);
    }
}
