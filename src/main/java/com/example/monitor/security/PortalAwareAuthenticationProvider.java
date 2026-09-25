package com.example.monitor.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * パスワードを照合したうえで、どのログイン画面から来たか（フォームの {@code portal}）と役割を突き合わせる。
 * 管理者用の画面からは ADMIN だけ、利用者用の画面からは ADMIN 以外だけを通す。
 *
 * <h2>なぜ成功ハンドラーではなく認証の段階で判定するか</h2>
 * 成功ハンドラーが呼ばれた時点で、セッションも LOGIN_SUCCESS の監査ログも既に作られている。
 * そこで取り消すと「成功した記録が残っているのに入れない」食い違いが生まれる。
 * 認証の段階で {@link BadCredentialsException} を投げれば、失敗ハンドラー・監査ログ（LOGIN_FAILURE）・
 * {@link LoginAttemptFilter} の試行回数制限が、パスワード違いと同じ経路でそのまま効く。
 * 画面に出る文言もパスワード違いと同じになり、アカウントの実在や役割を漏らさない。
 *
 * <h2>パスワードの照合もこのクラスが担う</h2>
 * {@link AuthenticationProvider} の Bean があると、Spring Boot は自動の
 * {@link DaoAuthenticationProvider} を作らなくなる。そのため内部で自前の
 * {@link DaoAuthenticationProvider} を持ち、照合（無効化された利用者の拒否を含む）を任せている。
 */
@Component
@Profile("!cli")
public class PortalAwareAuthenticationProvider implements AuthenticationProvider {

    /** 管理者用のログイン画面が送る {@code portal} の値。 */
    private static final String ADMIN_PORTAL = "admin";

    private final DaoAuthenticationProvider delegate;

    public PortalAwareAuthenticationProvider(AppUserDetailsService userDetailsService,
                                             PasswordEncoder passwordEncoder) {
        delegate = new DaoAuthenticationProvider(userDetailsService);
        delegate.setPasswordEncoder(passwordEncoder);
    }

    /**
     * 管理者用の画面から来た要求か。値が無い・不正なときは利用者用として扱い、
     * 管理者は明示的に管理者用の画面から来たときしか通さない。
     *
     * @param request ログインの要求
     * @return {@code portal=admin} なら true
     */
    static boolean isAdminPortal(HttpServletRequest request) {
        return ADMIN_PORTAL.equals(request.getParameter("portal"));
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Authentication result = delegate.authenticate(authentication);
        boolean adminPortal = authentication.getDetails() instanceof PortalDetails details && details.isAdminPortal();
        boolean admin = result.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (adminPortal != admin) {
            throw new BadCredentialsException("ログイン画面と権限が一致しません");
        }
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    /** ログイン要求の {@code portal} を認証処理まで運ぶ。 */
    public static class PortalDetails extends WebAuthenticationDetails {

        private static final long serialVersionUID = 1L;

        private final boolean adminPortal;

        public PortalDetails(HttpServletRequest request) {
            super(request);
            adminPortal = PortalAwareAuthenticationProvider.isAdminPortal(request);
        }

        /** @return 管理者用の画面から来た要求なら true */
        public boolean isAdminPortal() {
            return adminPortal;
        }
    }
}
