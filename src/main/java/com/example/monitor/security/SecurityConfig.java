package com.example.monitor.security;

import com.example.monitor.util.LoginReturnPath;

import com.example.monitor.repository.AppUserRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 認証・認可・CSRF・応答ヘッダーの設定。認可の考え方は {@code docs/user-portal-design.md} 3.4 に従う。
 *
 * <h2>認可の規則は上から順に最初に一致したものが使われる</h2>
 * {@link #filterChain} の {@code authorizeHttpRequests} に並べた {@code requestMatchers} は、
 * 書いた順に照合され、最初に一致した規則だけが使われる。個別のパスへの制限（ADMIN 専用など）は
 * 必ず最後の包括的な規則（{@code anyRequest().authenticated()}）より先に書くこと。
 * 逆にすると個別の制限が一切効かなくなる。
 * どのパスが誰に開いているかの一覧はここには書かない。規則を足すたびに一覧が古くなり、
 * 実装と食い違った説明を信じて誤った画面や API を書く元になるため、{@code filterChain} の並びを正とする。
 *
 * <p>既定を「認証済みなら誰でも」にしていても、管理者向けの画面と API は個別に ADMIN へ閉じている。
 * 以前は多くの管理者向け API がこの既定に落ちていて、一般利用者でもチャンネルの削除や
 * 全利用者の録画の閲覧ができてしまっていた。新しい管理者向けの画面・API を足すときは、
 * 既定に任せず ADMIN の規則を明示すること。
 *
 * <h2>設計書 3.4 の表と異なるところ</h2>
 * <ul>
 *   <li><b>DB 管理 API は {@code /api/tables/**} ではなく {@code /api/admin/tables/**}。</b>
 *       設計書のパス表記は概念的なもので、実装済みの {@code DatabaseTableController} は
 *       {@code /api/admin/tables} にマッピングされている。表の意図（DB 管理 API を ADMIN 限定にする）を
 *       実現するには実在するパスを保護する必要があるため、実際のパスに合わせた。</li>
 *   <li><b>{@code /h2-console/**} も ADMIN 限定にしている（設計書に無い自己判断）。</b>
 *       H2 コンソールは任意の SQL を実行できる、DB 管理画面と同格かそれ以上の生アクセス経路であり、
 *       設計書の意図（管理者以外に生の DB アクセスを与えない）に沿って同じ扱いにした。
 *       H2 コンソールの画面はフレームを使うため、フレーム表示の許可
 *       （{@code frameOptions().sameOrigin()}）も合わせて設定している。</li>
 *   <li><b>招待からの利用者登録とパスワードの再設定は未ログインでも開ける。</b>
 *       どちらもアカウントを使えない人が開く画面なので認証は掛けられない。代わりに管理者が発行した
 *       token（推測できない乱数・1 回限り・期限付き）が鍵になる。</li>
 *   <li><b>巡回の生存（{@code GET /api/health}）も未ログインで開ける。</b>外の見張りが叩くためで、返すのは状態と経過秒だけ。</li>
 * </ul>
 *
 * <h2>CSRF をどう有効にしているか</h2>
 * CSRF 対策は有効にしている。無効のままだと、悪意のあるページを管理者が開いただけで、
 * そのブラウザの権限で「チャンネル削除」「招待の発行」などを実行させられる
 * （ログイン中の Cookie が自動で送られるため。実際に別 Origin からの POST が通ることを確認した）。
 * そのため、変更系の API をトークン無しの素の {@code fetch} で呼ぶと 403 になる。
 * 画面からは {@code common.js} の共通処理を通して呼ぶこと。
 * <ul>
 *   <li><b>トークンは Cookie（{@code XSRF-TOKEN}）で配り、画面が {@code X-XSRF-TOKEN} ヘッダーで返す。</b>
 *       画面は静的 HTML と素の JS で、サーバー側でトークンを HTML に埋め込む仕組みを持たない。
 *       JS から読める Cookie（{@code withHttpOnlyFalse()}）で配れば、{@code common.js} の
 *       共通処理がヘッダーに載せるだけで全画面に効く。</li>
 *   <li><b>トークンは要求のたびにその場で確定させる（{@code csrfTokenRequestHandler()}）。</b>
 *       既定では実際に必要になるまで先延ばしされ、画面を開いただけでは Cookie が配られず、
 *       最初の POST が必ず失敗するという分かりにくい形で壊れるため。</li>
 *   <li><b>トークン不一致の 403 も {@link RequestAuthenticationHandler} で返す。</b>
 *       既定の処理ではエラー画面の HTML が返り、画面側が JSON のエラーメッセージとして扱えない。
 *       また、セッション切れの POST は認可より先に CSRF の検証で落ちるため、同じハンドラで
 *       未ログイン（401）と権限不足（403）を区別し、画面が再ログインを促せるようにしている。</li>
 * </ul>
 *
 * <h2>{@code @Profile("!cli")} を付けている理由</h2>
 * CLI（{@code cli} プロファイル）は Web サーバーを起動しないため、このクラスが定義する
 * {@link SecurityFilterChain} が依存する {@link HttpSecurity} は本来 Bean 化されない
 * （Servlet Web アプリケーションでしか提供されない）。それでも明示するのは、
 * {@code MonitoringController} で実際に「Web専用のBeanへの依存でCLIが起動できなくなる」
 * 事故が起きているため、同種のクラスには常に明示する方針にしているため
 * （{@code docs/pitfalls.md}「{@code cli} プロファイルで作られない Bean に依存するコントローラーには
 * {@code @Profile("!cli")} を付ける」参照）。
 */
@Configuration
@EnableWebSecurity
@Profile("!cli")
public class SecurityConfig {

    /**
     * ログイン処理・ログアウト処理を含む、認証まわりのパスの接頭辞。
     * {@code loginProcessingUrl} と {@code logoutUrl} をこの配下に置くことで、
     * 設計書 3.4 の「{@code /api/auth/**} は全員（ログイン処理自体）」をそのまま満たす。
     */
    private static final String AUTH_API_PREFIX = "/api/auth/**";

    /** 読み込みを許す出どころ。外部リソースを使っていないので自分自身だけに絞る。 */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            // style="display:none" のようなインラインの指定を使っているため
            "style-src 'self' 'unsafe-inline'",
            // チャンネルのアイコンを YouTube から直接読むため。Referrer-Policy: same-origin なので
            // 閲覧中の URL は YouTube へ渡らない
            "img-src 'self' data: https://yt3.ggpht.com https://yt3.googleusercontent.com",
            "media-src 'self'",
            "frame-src https://www.youtube.com https://www.youtube-nocookie.com https://player.twitch.tv",
            "connect-src 'self'",
            // <base> の差し替えで相対 URL の行き先を奪われるのを防ぐ
            "base-uri 'self'",
            // フォームの送信先を勝手に外部へ向けられるのを防ぐ
            "form-action 'self'",
            // X-Frame-Options と同じ意図（新しいブラウザはこちらを見る）
            "frame-ancestors 'self'");

    /**
     * CSRF トークンの受け渡し方を決める。
     *
     * <p>{@code setCsrfRequestAttributeName(null)} は<b>トークンを毎回その場で確定させる</b>
     * ための指定。既定では実際に必要になるまで先延ばしされるため、画面を開いただけでは
     * Cookie が配られず、最初の POST が必ず失敗するという分かりにくい形で壊れる。
     *
     * @return トークンの受け渡しを担う実装
     */
    private static CsrfTokenRequestAttributeHandler csrfTokenRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /**
     * 認可の規則・CSRF・応答ヘッダー・ログインとログアウトの挙動をまとめて定義する。
     *
     * <p>認可の規則は上から順に照合され、最初に一致したものが使われる（クラスの説明を参照）。
     *
     * @param http                              設定対象
     * @param successHandler                    ログイン成功時の処理（最終ログイン時刻の記録・遷移先の決定）
     * @param failureHandler                    ログイン失敗時の処理（アプリログと監査ログへの記録）
     * @param appUserRepository                 無効化・削除・パスワード変更の後の古いセッションを毎回の要求で落とすための照合先
     * @param loginAttemptLimiter               ログイン試行の回数制限（パスワード照合より前で打ち切る）
     * @param authenticationHandler             未ログイン・権限不足・CSRF 不一致を、API には JSON で返す処理
     * @param auditLogoutHandler                ログアウトを監査ログへ記録する処理
     * @return 構築したフィルターチェーン
     * @throws Exception Spring Security の設定 API がチェック例外を宣言しているため
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            AuthenticationSuccessHandler successHandler,
                                            AuthenticationFailureHandler failureHandler,
                                            AppUserRepository appUserRepository,
                                            LoginAttemptLimiter loginAttemptLimiter,
                                            RequestAuthenticationHandler authenticationHandler,
                                            AuditLogoutHandler auditLogoutHandler)
            throws Exception {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        // ログイン後に戻る先として正しい画面（GET）だけを保存する。保存は後の要求で上書きされるため、
        // ログイン画面を開いたブラウザが取りに行く /favicon.ico まで保存すると、開こうとしていた画面ではなく
        // そこへ戻してしまう（#222。既定の条件にあった favicon の除外が、条件を自前にしたときに消えていた）。
        // 除外を足さずに画面へ絞るのは、ほかにブラウザが勝手に取りに行くものでも同じことが起きるため。
        // パスは、戻る先（保存した要求の URL）と同じ復号前のもので照合する。
        requestCache.setRequestMatcher(request -> "GET".equals(request.getMethod())
                && LoginReturnPath.isPage(request.getRequestURI().substring(request.getContextPath().length())));
        http
            .requestCache(cache -> cache.requestCache(requestCache))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(authenticationHandler)
                    .accessDeniedHandler(authenticationHandler))
            .addFilterBefore(new LoginAttemptFilter(loginAttemptLimiter), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new ActiveAppUserFilter(appUserRepository), AuthorizationFilter.class)
            // CSRF 対策。無効のままだと、悪意のあるページを管理者が開いただけで
            // そのブラウザの権限で「チャンネル削除」「招待の発行」などを実行させられる
            // （ログイン中の Cookie が自動で送られるため。実際に別 Origin からの POST が
            // 通ることを確認済み）。トークンは Cookie で配り、画面側が
            // X-XSRF-TOKEN ヘッダで返す（common.js 参照）。
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfTokenRequestHandler())
                .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                    @Override
                    public <O extends CsrfFilter> O postProcess(O filter) {
                        filter.setAccessDeniedHandler(authenticationHandler);
                        return filter;
                    }
                }))
            .headers(headers -> headers
                .frameOptions(frameOptions -> frameOptions.sameOrigin())
                // 外部サイトへ遷移するときに、今いた URL を渡さない
                .referrerPolicy(referrer -> referrer.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                // 万一 HTML への差し込みを許してしまっても、外部スクリプトの読み込みと
                // インライン script の実行を止める。このアプリは外部リソースを
                // 一切読んでいないので 'self' だけで足りる
                // （style だけは style="display:none" を使っているため許可する）
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/userLogin.html", "/adminLogin.html", "/login", "/css/**", "/js/**", "/error").permitAll()
                // ブラウザがどの画面でも取りに行く。ログイン画面へ転送しても意味が無いので、
                // 未ログインでもそのまま返す（ファイルは無いので 404）
                .requestMatchers("/favicon.ico").permitAll()
                // 巡回の生存（#412）。外の見張り（cron・bin/service.sh status）がログイン無しで叩く。
                // 返すのは状態と経過秒だけ
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                // 招待リンクからの利用者登録。まだアカウントが無い時点で開くので認証は掛けられない。
                // 代わりに招待の token が鍵になる（推測できない乱数・1回限り・期限付き）
                .requestMatchers("/register.html", "/api/registration/**").permitAll()
                // パスワードの再設定（#324）。忘れてログインできない人が開くので認証は掛けられない。
                // 招待と同じく、管理者が発行した token が鍵になる（推測できない乱数・1回限り・24時間）
                .requestMatchers("/password-reset.html", "/api/password-reset/**").permitAll()
                .requestMatchers(AUTH_API_PREFIX).permitAll()
                .requestMatchers("/", "/index.html").hasRole("ADMIN")
                .requestMatchers("/tables.html", "/api/admin/tables/**").hasRole("ADMIN")
                .requestMatchers("/invitations.html", "/api/admin/invitations/**").hasRole("ADMIN")
                .requestMatchers("/logs.html", "/api/logs/**").hasRole("ADMIN")
                .requestMatchers("/api/settings/**").hasRole("ADMIN")
                .requestMatchers("/audit.html", "/api/audit-logs/**").hasRole("ADMIN")
                .requestMatchers("/h2-console/**").hasRole("ADMIN")
                // ここから下は段階4で追加したユーザー画面まわり。
                // 「自分の購読」は一般利用者の機能なので ADMIN 限定にはしない
                .requestMatchers("/my-channels.html", "/my-recordings.html", "/api/my/**").authenticated()
                // 利用者画面の 1 枚のページ（#146）。/my/** は MyShellController が /my.html へ forward する
                .requestMatchers("/my.html", "/my", "/my/**").authenticated()
                // プラットフォームの選択肢はユーザー画面の登録フォームでも使う
                .requestMatchers("/api/platforms/**").authenticated()
                // 管理者向けの画面と API。以前は anyRequest().authenticated() に落ちていたため、
                // 一般利用者でもチャンネルの削除や全利用者の録画閲覧ができてしまっていた。
                // ユーザー画面を追加するにあたって明示的に閉じる
                .requestMatchers("/users.html", "/api/admin/users/**").hasRole("ADMIN")
                .requestMatchers("/channels.html", "/api/channels/**").hasRole("ADMIN")
                .requestMatchers("/notifications.html", "/api/notifications/**").hasRole("ADMIN")
                // 再生画面は管理者だけ。画面が使う API（/api/recordings/**）が管理者専用で、利用者が開いても録画を読めない。
                // 利用者は 1 枚のページの /my/watch/<ID> で再生する（#178。#147 で利用者にも開けていたのを戻した）
                .requestMatchers("/recordings.html", "/player.html").hasRole("ADMIN")
                .requestMatchers("/api/recordings/**", "/api/downloads/**").hasRole("ADMIN")
                // 録画ファイルはログインしていれば全部見てよい（#419）。利用者のアーカイブは購読していない
                // チャンネルの録画も出すので、ファイルだけを購読で絞ると「一覧に出るのに再生できない」録画ができる
                .requestMatchers("/recordings/**").authenticated()
                .requestMatchers("/api/monitor/**", "/api/dashboard/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/userLogin.html")
                .loginProcessingUrl("/api/auth/login")
                // どの画面から来たかを PortalAwareAuthenticationProvider で役割と突き合わせる
                .authenticationDetailsSource(PortalAwareAuthenticationProvider.PortalDetails::new)
                .successHandler(successHandler)
                .failureHandler(failureHandler)
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .addLogoutHandler(auditLogoutHandler)
                .logoutSuccessUrl("/userLogin.html?logout")
                .permitAll());
        return http.build();
    }

    /**
     * パスワードのハッシュ化・照合に使うエンコーダ。
     *
     * <p>BCrypt を使うのは設計書 3.2 の指定どおり。タイミング攻撃対策やソルトの扱いを
     * 自前で正しく実装するのは現実的でないため、実装が枯れたアルゴリズムに任せる。
     *
     * @return BCrypt によるパスワードエンコーダ
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
