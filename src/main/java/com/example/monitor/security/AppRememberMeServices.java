package com.example.monitor.security;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.service.AuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

import java.time.LocalDateTime;

/**
 * 「ログインしたままにする」（remember-me）の Cookie を扱う。方式そのもの（ハッシュ方式・30 日・鍵のファイル）の
 * 理由は {@link SecurityConfig} のクラスの説明にある。ここで足しているのは、自動ログインの記録と、
 * 自分でパスワードを変えた端末の Cookie の作り直しの 2 つ。
 *
 * <ul>
 *   <li><b>自動ログインの記録は、このサブクラスの {@link #createSuccessfulAuthentication} で行う。</b>
 *       {@code RememberMeConfigurer} に成功ハンドラーを設定すると、{@code RememberMeAuthenticationFilter} は
 *       そのハンドラーを呼んだところで終わり、元の要求（開こうとした画面や API）が処理されない。
 *       このメソッドは {@code AbstractRememberMeServices.autoLogin()} が Cookie の署名・期限・利用者の有効性を
 *       すべて確かめた後にだけ呼ぶので、ここで記録すれば成功した自動ログインだけが残る。
 *       この記録の後で {@code AuthenticationManager} が鍵を照合するが、鍵は同じこのクラスの {@link #getKey()} を
 *       {@link SecurityConfig} が渡しているので、ここで記録した成功が後で覆ることは無い。</li>
 *   <li><b>新しい {@link AuditAction} を足さず、{@link AuditAction#LOGIN_SUCCESS} に補足
 *       （{@link #AUTO_LOGIN_DETAIL}）を付けて記録する。</b>監査ログ画面の選択肢（{@code audit.html}）と
 *       表示名（{@code audit.js}）を変えずに、「ログイン成功」で絞り込めばフォームのログイン（補足なし）と
 *       自動ログインの両方が並ぶようにするため。</li>
 *   <li><b>自動ログインの失敗は記録しない。</b>{@code onLoginFail} はフォームのログインの失敗でも呼ばれ
 *       （{@link LoggingAuthenticationFailureHandler} の記録と二重になる）、{@code autoLogin} の失敗は
 *       そもそも {@code onLoginFail} を通らない。さらに、壊れた Cookie を付けた要求は未ログインの誰でも
 *       送れるので、記録すると監査ログを際限なく増やせてしまう。</li>
 *   <li><b>友人用の入口（{@link FriendGate}）では、管理者の Cookie で自動ログインしない。</b>
 *       {@link #processAutoLoginCookie} で断り、Cookie も消す（理由はそのメソッドの説明にある）。</li>
 *   <li><b>同時に届いた複数の要求がそれぞれ自動ログインし、{@link AuditAction#LOGIN_SUCCESS} が数件並ぶことがある。</b>
 *       セッションが切れた直後に画面を開くと、画面と API の要求がまだセッションを持たないまま並んで届くため。
 *       どれも同じ端末・同じ利用者の正しい自動ログインなので、許容している。</li>
 * </ul>
 */
@Slf4j
public class AppRememberMeServices extends TokenBasedRememberMeServices {

    /** 監査ログの補足。フォームのログインの {@link AuditAction#LOGIN_SUCCESS} は補足が {@code null} なので、これで見分ける。 */
    static final String AUTO_LOGIN_DETAIL = "「ログインしたままにする」の Cookie による自動ログイン";

    private final AppUserRepository appUserRepository;
    private final AuditLogger auditLogger;

    /**
     * 署名の方式は SHA256 に固定する。{@code @Component} にせず {@link SecurityConfig} の {@code @Bean} で作るのは、
     * 鍵ファイルの読み込み（{@link RememberMeKeyFile}）と期限・{@code Secure} の設定を {@link SecurityConfig} に集めておくため。
     *
     * @param key                Cookie に署名する鍵（{@link RememberMeKeyFile} から読んだもの）
     * @param userDetailsService Cookie の利用者名から利用者を読み直す先（無効化・削除された利用者はここで弾かれる）
     * @param appUserRepository  自動ログインで最終ログイン時刻を更新する先
     * @param auditLogger        自動ログインを監査ログへ記録する先
     */
    public AppRememberMeServices(String key, UserDetailsService userDetailsService,
                                 AppUserRepository appUserRepository, AuditLogger auditLogger) {
        super(key, userDetailsService, RememberMeTokenAlgorithm.SHA256);
        this.appUserRepository = appUserRepository;
        this.auditLogger = auditLogger;
    }

    /**
     * Cookie の署名・期限・利用者を確かめたうえで、友人用の入口（{@link FriendGate}）から来た管理者の Cookie を断る。
     *
     * <p>自動ログインは {@link PortalAwareAuthenticationProvider}（フォームのログイン専用）を通らないので、
     * ここで断らないと、管理者の Cookie を持つブラウザは関所からも管理者として入れる。
     * {@link RememberMeAuthenticationException} を投げると、親の {@code autoLogin} がこの入口の Cookie を消して
     * 未ログインのまま進める。{@link #createSuccessfulAuthentication} を通らないので、自動ログインの記録も残らない。
     *
     * @param tokens   Cookie を分解したもの
     * @param request  自動ログインしようとしている要求
     * @param response Cookie を消すときに使う応答
     * @return Cookie の利用者
     * @throws RememberMeAuthenticationException 友人用の入口から来た管理者の Cookie のとき
     */
    @Override
    protected UserDetails processAutoLoginCookie(String[] tokens, HttpServletRequest request,
                                                 HttpServletResponse response) {
        UserDetails user = super.processAutoLoginCookie(tokens, request, response);
        if (FriendGate.matches(request) && FriendGate.isAdmin(user.getAuthorities())) {
            log.warn("友人用の入口で管理者の「ログインしたまま」の Cookie を断りました: user={}", user.getUsername());
            throw new RememberMeAuthenticationException("友人用の入口では管理者として自動ログインしない");
        }
        return user;
    }

    /**
     * Cookie による自動ログインが成功したときに、最終ログイン時刻と監査ログを残す。
     * フォームのログインでは {@link RoleBasedAuthenticationSuccessHandler} が同じことをしており、
     * 自動ログインはそこを通らないため（クラスの説明を参照）。
     *
     * @param request 自動ログインした要求
     * @param user    Cookie の利用者名から読み直した利用者
     * @return 自動ログインの認証結果
     */
    @Override
    protected Authentication createSuccessfulAuthentication(HttpServletRequest request, UserDetails user) {
        Authentication authentication = super.createSuccessfulAuthentication(request, user);
        Long userId = user instanceof AuthenticatedAppUser appUser ? appUser.getUserId() : null;
        if (userId != null) {
            appUserRepository.updateLastLoginAt(userId, LocalDateTime.now());
        }
        log.info("「ログインしたままにする」の Cookie で自動ログインしました: user={}", user.getUsername());
        auditLogger.recordAuthEvent(AuditAction.LOGIN_SUCCESS, AuditOutcome.SUCCESS,
                userId, user.getUsername(), request.getRemoteAddr(), AUTO_LOGIN_DETAIL);
        return authentication;
    }

    /**
     * 自分でパスワードを変えた端末の「ログインしたまま」の Cookie を、新しいパスワードのハッシュで作り直す。
     * Cookie の署名にはパスワードのハッシュが入るので、作り直さないとこの端末の Cookie も無効になり、
     * セッションが切れた時点でログイン画面に戻されてしまう。
     *
     * <p>作り直した Cookie の期限は<b>その時点から 30 日</b>になる（元の期限を引き継がない）。
     * 今のパスワードを確かめたうえでの変更なので、チェックを付けてログインし直したのと同じ扱いにしている。
     *
     * <p>「ログインしたまま」を選んでいない端末（Cookie が無い）には作らない。また、Cookie の利用者名が
     * 変えた本人と違うときも作り直さない。共用の端末に<b>別の利用者</b>の Cookie が残っていると、
     * それをこの利用者のものに書き換えてしまい、次に開いた人がこの利用者として自動ログインするため。
     *
     * @param request        パスワードを変えた要求（今の Cookie を読む）
     * @param response       作り直した Cookie を載せる応答
     * @param authentication 新しいパスワードのハッシュを持つ主体の認証結果（資格情報を消す前のもの）
     */
    public void reissueAfterPasswordChange(HttpServletRequest request, HttpServletResponse response,
                                           Authentication authentication) {
        String cookieValue = extractRememberMeCookie(request);
        if (cookieValue == null || cookieValue.isEmpty()) {
            return;
        }
        String[] tokens;
        try {
            tokens = decodeCookie(cookieValue);
        } catch (InvalidCookieException e) {
            return;
        }
        if (tokens.length == 0 || !tokens[0].equals(authentication.getName())) {
            return;
        }
        // loginSuccess() は要求の remember-me パラメーターを見るので、JSON の PUT では Cookie を作らない
        onLoginSuccess(request, response, authentication);
    }
}
