package com.example.monitor.security;

import com.example.monitor.util.ApiRequestPath;

import com.example.monitor.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * ログイン時だけの有効性判定では、無効化・削除・パスワードの変更の後もセッションが使えるため毎回照合する。
 * パスワードを変えた本人のセッションは、変更時に主体を差し替えているので落ちない（{@code MyAccountController}）。
 */
@RequiredArgsConstructor
public class ActiveAppUserFilter extends OncePerRequestFilter {
    private final AppUserRepository repository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedAppUser user
                && !repository.isSessionValid(user.getUserId(), user.getPasswordChangedAt())) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            if (ApiRequestPath.matches(request)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"ログインし直してください\"}");
            } else {
                response.sendRedirect(request.getContextPath() + "/userLogin.html");
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
