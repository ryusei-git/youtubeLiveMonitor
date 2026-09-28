package com.example.monitor.controller;

import com.example.monitor.dto.PasswordChangeRequest;
import com.example.monitor.entity.AppUser;
import com.example.monitor.security.AppRememberMeServices;
import com.example.monitor.security.AuthenticatedAppUser;
import com.example.monitor.service.PasswordChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ログイン中の利用者自身のアカウントを扱う API。一般利用者も管理者も使う（{@code /api/my/**} は認証だけを求める）。
 *
 * <p>対象は常にログイン中の本人にする（{@link MyNotificationSettingsController} と同じ理由）。
 *
 * <p>{@link PasswordChangeService} が cli プロファイルで作られないため {@code @Profile("!cli")}
 * （{@code docs/pitfalls.md}。付け忘れると CLI が起動できなくなる）。
 */
@Profile("!cli")
@RestController
@RequestMapping("/api/my")
@RequiredArgsConstructor
public class MyAccountController {

    /** SecurityConfig は既定のセッション保存先を使っているので、同じ既定の実装に保存する。 */
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    private final PasswordChangeService passwordChangeService;
    private final AppRememberMeServices rememberMeServices;

    /**
     * 自分のパスワードを変える。ほかのセッションは次のリクエストでログアウトになる。
     *
     * <p>このセッションだけは、新しい変更時刻を持つ主体に差し替えて使い続けられるようにする。
     * 差し替えないと、変更した本人まで {@code ActiveAppUserFilter} に落とされる。
     *
     * <p>この端末で「ログインしたまま」にしていたら、その Cookie も新しいパスワードで作り直す
     * （ほかの端末の Cookie は無効のまま）。
     *
     * @param principal ログイン中の利用者
     * @param body      今のパスワードと新しいパスワード
     * @param request   主体の差し替えを保存するセッションの要求
     * @param response  同上の応答。「ログインしたまま」の Cookie を作り直したときはそれも載せる
     * @return 204。今のパスワードが違う・新しいパスワードが短いときは 400、
     *         今のパスワードを続けて間違えて一時的に制限しているときは 429（{@code GlobalExceptionHandler}）
     */
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthenticatedAppUser principal,
                                               @RequestBody PasswordChangeRequest body,
                                               HttpServletRequest request, HttpServletResponse response) {
        AppUser user = passwordChangeService.changePassword(principal.getUserId(),
                body.currentPassword(), body.newPassword(), request.getRemoteAddr());

        AuthenticatedAppUser refreshed = new AuthenticatedAppUser(user);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                refreshed, null, refreshed.getAuthorities());
        // この端末の「ログインしたまま」は古いハッシュで署名してあり無効になるので、新しいハッシュで作り直す
        rememberMeServices.reissueAfterPasswordChange(request, response, authentication);
        // ログイン時と同じく、セッションにパスワードのハッシュを残さない
        refreshed.eraseCredentials();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }
}
