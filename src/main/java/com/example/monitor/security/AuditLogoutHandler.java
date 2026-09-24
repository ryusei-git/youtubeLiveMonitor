package com.example.monitor.security;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.service.AuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * ログアウトを監査ログへ記録する。
 *
 * <p>{@link LogoutHandler} として実装しているのは、{@code logoutSuccessUrl} 到達時点では
 * 認証情報が既にクリアされておりログアウトした本人が分からなくなるため。
 * {@link LogoutHandler} は認証情報のクリアより前に呼ばれるので、
 * 誰がログアウトしたかをこの時点でまだ取得できる。
 *
 * <p>{@link ActiveAppUserFilter} が強制的にセッションを失効させる経路（無効化・削除された
 * 利用者）は {@code SecurityContextLogoutHandler} を直接呼び出しており、この
 * {@link LogoutHandler} チェーンを経由しない。そちらを「利用者自身によるログアウト」と
 * 同じ扱いで記録するのは意味が違う（本人の操作ではなく強制失効のため）ので、意図的に分けている。
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
public class AuditLogoutHandler implements LogoutHandler {

    private final AuditLogger auditLogger;

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication == null) {
            return;
        }
        Long userId = authentication.getPrincipal() instanceof AuthenticatedAppUser user ? user.getUserId() : null;
        auditLogger.recordAuthEvent(AuditAction.LOGOUT, AuditOutcome.SUCCESS,
                userId, authentication.getName(), request.getRemoteAddr(), null);
    }
}
