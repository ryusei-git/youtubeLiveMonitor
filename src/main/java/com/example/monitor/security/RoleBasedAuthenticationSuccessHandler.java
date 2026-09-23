package com.example.monitor.security;

import com.example.monitor.repository.AppUserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * ログイン成功時に、最終ログイン時刻の記録と権限に応じた遷移先の決定を行う。
 *
 * <p>{@link SavedRequestAwareAuthenticationSuccessHandler} を継承しているのは、保護された
 * ページへの直接アクセスからログイン画面へ飛ばされた場合に、ログイン後元のページへ戻す
 * （Spring Security 標準の挙動）ためで、素の {@code SimpleUrlAuthenticationSuccessHandler}
 * だとこの挙動が無い。
 *
 * <p>直接アクセスの経由が無い（ログイン画面を直接開いた）場合の遷移先は権限で分ける。
 * {@code /index.html}（ダッシュボード）は ADMIN 専用（設計書 3.4）なので、一般利用者を
 * そのまま送るとその場で 403 になってしまうため。
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
@Slf4j
public class RoleBasedAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    /** ADMIN のログイン後の既定の遷移先。 */
    private static final String ADMIN_DEFAULT_TARGET = "/index.html";

    /** ADMIN 以外のログイン後の既定の遷移先。{@code /index.html} も {@code /channels.html} も
     *  ADMIN 専用なので、一般利用者は自分の購読一覧へ送る。 */
    private static final String NON_ADMIN_DEFAULT_TARGET = "/my-channels.html";

    private final AppUserRepository appUserRepository;

    /**
     * ログイン成功時の処理。最終ログイン時刻を記録し、権限に応じた遷移先を設定してから
     * 親クラスの標準処理（元ページへの復帰・遷移）に委譲する。
     *
     * @param request        リクエスト
     * @param response       レスポンス
     * @param authentication 認証結果
     * @throws ServletException 親クラスの処理で発生しうる例外
     * @throws IOException      リダイレクト処理で発生しうる例外
     */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws ServletException, IOException {
        String username = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
        setDefaultTargetUrl(isAdmin ? ADMIN_DEFAULT_TARGET : NON_ADMIN_DEFAULT_TARGET);

        appUserRepository.findByUsername(username)
                .ifPresent(user -> appUserRepository.updateLastLoginAt(user.getId(), LocalDateTime.now()));
        log.info("ログインに成功しました: user={}", username);

        super.onAuthenticationSuccess(request, response, authentication);
    }
}
