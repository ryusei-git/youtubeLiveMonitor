package com.example.monitor.security;

import com.example.monitor.util.ApiRequestPath;

import com.example.monitor.repository.AppUserRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.ObjectPostProcessor;
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
 * 認証・認可の設定。認可ルールは {@code docs/user-portal-design.md} 3.4 の表をそのまま実装する。
 *
 * <p>{@code requestMatchers} は先に書いたものから順に評価される。個別のパスへの制限
 * （ADMIN専用など）を、包括的なルール（{@code anyRequest().authenticated()}）より
 * 先に書くこと。逆にすると個別の制限が一切効かなくなる。
 *
 * <table>
 *   <caption>設計書 3.4 の表と実装の対応</caption>
 *   <tr><th>設計書の記載</th><th>実際のパス</th><th>権限</th></tr>
 *   <tr><td>/login, /css/**, /js/**</td><td>同左（+ /login.html, /admin-login.html, /error）</td><td>全員</td></tr>
 *   <tr><td>/api/auth/**</td><td>同左（ログイン処理・ログアウト）</td><td>全員</td></tr>
 *   <tr><td>/tables.html, /api/tables/**</td>
 *       <td>/tables.html, <b>/api/admin/tables/**</b></td><td>ADMIN</td></tr>
 *   <tr><td>/logs.html, /api/logs/**</td><td>同左</td><td>ADMIN</td></tr>
 *   <tr><td>/api/settings/**</td><td>同左</td><td>ADMIN</td></tr>
 *   <tr><td>/audit.html, /api/audit-logs/**</td><td>同左（段階2で追加予定。先取りで設定）</td><td>ADMIN</td></tr>
 *   <tr><td>/index.html</td><td>同左（+ ルート "/"）</td><td>ADMIN</td></tr>
 *   <tr><td>/recordings/**</td><td>同左</td><td>認証済み</td></tr>
 *   <tr><td>その他</td><td>同左</td><td>認証済み</td></tr>
 * </table>
 *
 * <p><b>{@code /api/tables/**} ではなく {@code /api/admin/tables/**} にしている理由。</b>
 * 設計書のパス表記は概念的なもので、実装済みの {@code DatabaseTableController} は
 * 実際には {@code /api/admin/tables} にマッピングされている（設計書執筆時点の想定と
 * 既存実装の食い違い）。表の意図（DB管理APIをADMIN限定にする）を実現するには
 * 実在するパスを保護する必要があるため、実装済みの物理パスに合わせた。
 *
 * <p><b>設計書に無い自己判断: {@code /h2-console/**} も ADMIN 限定にしている。</b>
 * H2 コンソールは任意の SQL を実行できる、DB管理画面（{@code /tables.html}）と同格かそれ以上の
 * 生アクセス経路であり、設計書の意図（管理者以外に生の DB アクセスを与えない）に沿って
 * 同じ扱いにした。H2 コンソールの画面はフレームを使うため、フレーム表示の許可
 * （{@code frameOptions().sameOrigin()}）も合わせて設定している。
 *
 * <h2>CSRF を無効化している理由</h2>
 * 既存の画面はすべて素の {@code fetch} で API を呼んでおり（{@code common.js} の
 * {@code apiPost}/{@code apiPut}/{@code apiDelete} 参照）、CSRF トークンをヘッダーに
 * 載せる仕組みを持たない。CSRF を有効にすると、ログイン以外の<b>既存の全ての変更系 API が
 * 一斉に 403 になる</b>（チャンネル登録・録画削除・設定変更など）。
 * 設計書 9 章が「CSRF 対策の有効化」を<b>不特定多数へ公開する場合に追加で必要なこと</b>
 * として切り出しているのは、0 章の前提（信頼できる少人数・LAN/Tailscale 限定）では
 * このリスクを許容する判断だと読み取れる。将来、公開範囲を広げる際は設計書 9 章に従って
 * 有効化し、フロントエンド側にもトークンの受け渡しを実装する必要がある。
 *
 * <h2>{@code @Profile("!cli")} を付けている理由</h2>
 * CLI（{@code cli} プロファイル）は Web サーバーを起動しないため、このクラスが定義する
 * {@link SecurityFilterChain} が依存する {@link HttpSecurity} は本来 Bean 化されない
 * （Servlet Web アプリケーションでしか提供されない）。それでも明示するのは、
 * {@code MonitoringController} で実際に「Web専用のBeanへの依存でCLIが起動できなくなる」
 * 事故が起きているため、同種のクラスには常に明示する方針にしているため（CLAUDE.md 参照）。
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

    /**
     * 認可ルールとログイン・ログアウトの挙動を定義する。
     *
     * @param http              設定対象
     * @param successHandler    ログイン成功時の処理（最終ログイン時刻の記録・遷移先の決定）
     * @param failureHandler    ログイン失敗時の処理（WARN ログの記録）
     * @return 構築したフィルターチェーン
     * @throws Exception Spring Security の設定 API がチェック例外を宣言しているため
     */
    /** 読み込みを許す出どころ。外部リソースを使っていないので自分自身だけに絞る。 */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            // style="display:none" のようなインラインの指定を使っているため
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            AuthenticationSuccessHandler successHandler,
                                            AuthenticationFailureHandler failureHandler,
                                            RecordingFileAuthorizationManager recordingFileAuthorizationManager,
                                            AppUserRepository appUserRepository,
                                            LoginAttemptLimiter loginAttemptLimiter,
                                            RequestAuthenticationHandler authenticationHandler,
                                            AuditLogoutHandler auditLogoutHandler)
            throws Exception {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        // APIの要求本文や変更操作を、再ログイン後の復帰要求として保存しない。
        requestCache.setRequestMatcher(request -> "GET".equals(request.getMethod())
                && !ApiRequestPath.matches(request));
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
                .requestMatchers("/login.html", "/admin-login.html", "/login", "/css/**", "/js/**", "/error").permitAll()
                // 招待リンクからの利用者登録。まだアカウントが無い時点で開くので認証は掛けられない。
                // 代わりに招待の token が鍵になる（推測できない乱数・1回限り・期限付き）
                .requestMatchers("/register.html", "/api/registration/**").permitAll()
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
                // プラットフォームの選択肢はユーザー画面の登録フォームでも使う
                .requestMatchers("/api/platforms/**").authenticated()
                // 管理者向けの画面と API。以前は anyRequest().authenticated() に落ちていたため、
                // 一般利用者でもチャンネルの削除や全利用者の録画閲覧ができてしまっていた。
                // ユーザー画面を追加するにあたって明示的に閉じる
                .requestMatchers("/users.html", "/api/admin/users/**").hasRole("ADMIN")
                .requestMatchers("/channels.html", "/api/channels/**").hasRole("ADMIN")
                .requestMatchers("/notifications.html", "/api/notifications/**").hasRole("ADMIN")
                .requestMatchers("/recordings.html", "/player.html").hasRole("ADMIN")
                .requestMatchers("/api/recordings/**", "/api/downloads/**").hasRole("ADMIN")
                // 録画ファイルは「管理者は全部、一般利用者は購読しているチャンネルのぶんだけ」。
                // 誰がどれを購読しているかを見ないと決まらないので、静的なルールでは表せない
                .requestMatchers("/recordings/**").access(recordingFileAuthorizationManager)
                .requestMatchers("/api/monitor/**", "/api/dashboard/**").hasRole("ADMIN")
                .requestMatchers("/playground.html", "/api/playground/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/api/auth/login")
                // どの画面から来たかを PortalAwareAuthenticationProvider で役割と突き合わせる
                .authenticationDetailsSource(PortalAwareAuthenticationProvider.PortalDetails::new)
                .successHandler(successHandler)
                .failureHandler(failureHandler)
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .addLogoutHandler(auditLogoutHandler)
                .logoutSuccessUrl("/login.html?logout")
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
