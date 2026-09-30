package com.example.monitor.security;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.service.AuditLogger;
import com.example.monitor.util.ApiRequestPath;
import com.example.monitor.util.LoginReturnPath;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import java.io.IOException;

/**
 * API へログイン画面の HTML を返さず、セッション切れ（401）と権限不足（403）を区別する。
 * あわせて、ログイン済みの 403 を監査ログ（{@link AuditAction#ACCESS_DENIED}）へ記録する。
 *
 * <h2>どの拒否を記録するか</h2>
 * <ul>
 *   <li><b>ログイン済みの 403 は、API か画面かを問わず記録する。</b>一般利用者が {@code /tables.html} や
 *       {@code /h2-console/} を開くのも権限外の試行で、試行そのものが不正の証跡になる。
 *       以前は API だけを記録していて、画面を探っても何も残らなかった。</li>
 *   <li><b>未ログイン（401・ログイン画面への転送）は記録しない。</b>誰の操作か特定できないまま
 *       監査ログが埋もれるだけになるため（#49）。</li>
 *   <li><b>サイトの入口（{@code /}）は記録せず、一般利用者は利用者のトップへ送る。</b>
 *       管理者の画面だが、エラー画面の「トップへ戻る」やブックマークから一般利用者も自然に開く。
 *       未ログインで入口を開いてからログインした一般利用者も、保存された入口の要求へ戻される。
 *       記録すると権限外の探りが埋もれ、403 の画面を出すと「トップへ戻る」を押しただけで
 *       「権限がありません」と言われる。送るのは GET だけ（GET には CSRF の検証が無いので、
 *       入口の GET で拒まれるのは管理者でない利用者だけ）。</li>
 *   <li><b>CSRF トークンの不一致は detail に {@code csrf=true} を付ける。</b>{@code CsrfFilter} の拒否も
 *       このクラスへ来る（{@code SecurityConfig}）。付けないと、役割の不足（権限外の探り）と、
 *       トークンの無い送信（別サイトからの送信の試み・手で組み立てた要求）を管理者が見分けられない。</li>
 *   <li><b>「ログインしたまま」（remember-me）でのログインで拒否されたときも 403 にして記録する。</b>
 *       Spring の {@code ExceptionTranslationFilter} は、remember-me のログインの拒否を
 *       「ちゃんとログインし直せば通るかもしれない」として {@link #commence} へ回す。
 *       このアプリの認可の規則は役割だけで決まり（{@code fullyAuthenticated()} を使っていない）、
 *       同じ利用者がログインし直しても結果は変わらない。そのまま 401 やログイン画面への転送にすると、
 *       記録が残らないうえ、利用者は意味の無いログインをやり直させられる。
 *       フォームでログインしたセッションの 403 と同じ応答にそろえる。
 *       {@code fullyAuthenticated()} の規則を足すときは、ここを見直すこと。</li>
 * </ul>
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
public class RequestAuthenticationHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    /** remember-me でのログインかを、ExceptionTranslationFilter と同じ判定で見分けるため。 */
    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    /**
     * サイトの入口。拒否しても監査ログには残さず、一般利用者は {@code USER_TOP} へ送る
     * （クラスの説明を参照）。
     */
    private static final String SITE_ROOT = "/";

    /**
     * 入口で拒んだ一般利用者を送る先。{@code RoleBasedAuthenticationSuccessHandler} が
     * 一般利用者をログイン後に送る先と同じ、利用者画面のトップ。
     */
    private static final String USER_TOP = "/my";

    private final AuditLogger auditLogger;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException, ServletException {
        // remember-me でのログインで拒否されると、ExceptionTranslationFilter は AccessDeniedHandler ではなくここへ回す
        // （ちゃんとログインし直せば通るかもしれない、という Spring の既定の考え）。このアプリの規則は役割だけで決まり、
        // ログインし直しても通らないので、ログイン済みの権限不足として扱う（クラスの説明を参照）
        Authentication rememberMe = exception.getAuthenticationRequest();
        if (exception instanceof InsufficientAuthenticationException
                && exception.getCause() instanceof AccessDeniedException denied
                && TRUST_RESOLVER.isRememberMe(rememberMe)) {
            deny(request, response, denied, rememberMe);
            return;
        }
        if (ApiRequestPath.matches(request)) {
            writeError(response, 401, "ログインし直してください");
        } else {
            // 管理者の画面からは管理者用のログイン画面へ送る。利用者用の画面からは管理者はログインできないため
            String loginPage = LoginReturnPath.isAdminPage(request.getServletPath()) ? "/adminLogin.html" : "/userLogin.html";
            new LoginUrlAuthenticationEntryPoint(loginPage).commence(request, response, exception);
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        deny(request, response, exception, SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 拒否した要求に応答し、ログイン済みなら監査ログへ記録する。入口（{@code /}）を開いた
     * 一般利用者は、403 の画面ではなく利用者のトップへ送る（クラスの説明を参照）。
     *
     * <p>主体を引数で受け取るのは、{@link #commence} から呼ぶときは
     * {@code ExceptionTranslationFilter} が {@code SecurityContextHolder} を空にした後で、
     * 主体を例外からしか取り出せないため。
     *
     * @param request        拒否した要求
     * @param response       応答
     * @param exception      拒否の理由（CSRF の不一致なら {@link CsrfException}）
     * @param authentication 拒否した時点の主体。未ログインなら {@code null} か匿名の主体
     * @throws IOException      応答の書き込みに失敗した場合
     * @throws ServletException エラー画面への転送に失敗した場合
     */
    private void deny(HttpServletRequest request, HttpServletResponse response,
                      AccessDeniedException exception, Authentication authentication)
            throws IOException, ServletException {
        boolean loggedIn = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        // 401（未ログイン）は記録しない。ログインしていない相手はここでしか操作を追えず、
        // 誰の操作かも特定できないまま監査ログが埋もれるだけになるため（Issue #49）。
        // 入口（/）は利用者も自然に開くので記録しない（クラスの説明を参照）
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (loggedIn && !SITE_ROOT.equals(path)) {
            recordAccessDenied(request, authentication, exception instanceof CsrfException);
        }
        if (ApiRequestPath.matches(request)) {
            // セッション切れのPOSTはCSRF検証が先に失敗するため、ここでも未認証を判定する。
            writeError(response, loggedIn ? 403 : 401,
                    loggedIn ? "操作が許可されていません。権限を確認し、必要なら画面を更新してください" : "ログインし直してください");
        } else if (loggedIn && SITE_ROOT.equals(path) && "GET".equals(request.getMethod())) {
            // 入口の GET で拒まれるのは管理者でない利用者だけ（GET には CSRF の検証が無い）。
            // 403 の画面ではなく利用者のトップへ送る（クラスの説明を参照）
            response.sendRedirect(request.getContextPath() + USER_TOP);
        } else {
            new AccessDeniedHandlerImpl().handle(request, response, exception);
        }
    }

    /**
     * ログイン済みの 403 を監査ログへ記録する。
     *
     * <p>{@link AuthenticatedAppUser} が保持する利用者IDをそのまま使う。
     * {@link RoleBasedAuthenticationSuccessHandler} 等と違いここではリポジトリを持たないため、
     * 名前からIDを引き直さず、認証情報に既に載っている値を使う
     * （{@link AuditLogoutHandler} と同じ考え方）。
     *
     * @param request        拒否されたリクエスト（役割の不足か CSRF トークンの不一致）
     * @param authentication ログイン済みの認証情報
     * @param csrf           CSRF トークンの不一致で拒否したか。{@code detail} に {@code csrf=true} を付けて、
     *                       役割の不足と見分けられるようにする
     */
    private void recordAccessDenied(HttpServletRequest request, Authentication authentication, boolean csrf) {
        Long userId = authentication.getPrincipal() instanceof AuthenticatedAppUser user ? user.getUserId() : null;
        auditLogger.record(AuditAction.ACCESS_DENIED, AuditOutcome.FAILURE, userId, authentication.getName(),
                request.getRemoteAddr(), "PATH", request.getRequestURI(),
                "method=" + request.getMethod() + (csrf ? ", csrf=true" : ""));
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
