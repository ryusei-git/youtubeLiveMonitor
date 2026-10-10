package com.example.monitor.security;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AppUser.Role;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditLog;
import com.example.monitor.entity.Invitation;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.AuditLogRepository;
import com.example.monitor.repository.InvitationRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code SecurityConfig} が組み立てる認可ルール・ログイン・ログアウトの結合テスト。
 *
 * <p>純粋な単体テスト（Mockito）では Spring Security のフィルターチェーンそのものを
 * 検証できないため、このクラスに限り実際の Spring コンテキストと {@link MockMvc} を使う
 * （本プロジェクトで初めてのこの種のテスト。既存のテストは全て Mockito ベースの単体テスト）。
 * 対象DBは {@code src/test/resources/application.yml} でインメモリH2に切り替えており、
 * 本番の {@code data/monitor.mv.db} には触れない。
 *
 * <p>初期管理者は同じ {@code application.yml} の {@code monitor.admin.*} から
 * {@link AdminUserInitializer} が実際に作成したものを使う（モックしない）。
 * これにより「起動時に初期管理者が作られ、その認証情報でログインできる」という
 * 一連の流れそのものを検証できる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SecurityConfig")
class SecurityConfigTest {

    /** src/test/resources/application.yml の monitor.admin.* と対応する初期管理者の認証情報。 */
    private static final String ADMIN_USERNAME = "testadmin";
    private static final String ADMIN_PASSWORD = "test-admin-password";

    private static final String NORMAL_USERNAME = "normal-user";
    private static final String NORMAL_PASSWORD = "normal-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * USER権限のテスト用アカウントを用意する。{@link AdminUserInitializer} は ADMIN しか
     * 作らないため、USER 側はテストで直接用意する必要がある。
     */
    @BeforeEach
    void ensureNormalUserExists() {
        if (appUserRepository.findByUsername(NORMAL_USERNAME).isEmpty()) {
            appUserRepository.save(
                    new AppUser(NORMAL_USERNAME, passwordEncoder.encode(NORMAL_PASSWORD), Role.USER));
        }
    }

    /** {@code createUser()} で作る利用者（名前は {@code t04-} で始まる）のパスワード。 */
    private static final String T04_PASSWORD = "t04-password-1";

    /** 「ログインしたままにする」の Cookie の名前（Spring Security の既定）。 */
    private static final String REMEMBER_ME = "remember-me";

    /** 「ログインしたままにする」の期間（SecurityConfig の REMEMBER_ME_SECONDS と同じ 30 日）。 */
    private static final int REMEMBER_ME_SECONDS = 30 * 24 * 60 * 60;

    /** 権限外の 403 の応答の文言（RequestAuthenticationHandler）。 */
    private static final String FORBIDDEN_MESSAGE = "操作が許可されていません。権限を確認し、必要なら画面を更新してください";

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    /**
     * テスト用の利用者を作る。同じ名前が既にあればそれを返す。
     *
     * <p>このクラスは Spring のコンテキストとインメモリ H2 を {@code AppUserManagementControllerTest} と共有し
     * （コンテキストのキャッシュ）、テストの間で DB は消えない。パスワードの変更や無効化が別のテストに漏れないよう、
     * 名前はテストごとに変える。
     */
    private AppUser createUser(String username, Role role) {
        return appUserRepository.findByUsername(username).orElseGet(() ->
                appUserRepository.save(new AppUser(username, passwordEncoder.encode(T04_PASSWORD), role)));
    }

    /** フォームでログインする。rememberMe なら「ログインしたままにする」、adminPortal なら管理者用の画面から送る。 */
    private MvcResult login(String username, String password, boolean rememberMe, boolean adminPortal) throws Exception {
        return login(username, password, rememberMe, adminPortal, null);
    }

    /**
     * フォームでログインする。friendGate が null でなければ、友人用の入口（関所）を通ったものとして
     * {@link FriendGate#HEADER} をその値で付ける。
     */
    private MvcResult login(String username, String password, boolean rememberMe, boolean adminPortal,
                            String friendGate) throws Exception {
        RequestBuilder form = SecurityMockMvcRequestBuilders.formLogin("/api/auth/login")
                .user(username).password(password);
        return mockMvc.perform(context -> {
            MockHttpServletRequest request = form.buildRequest(context);
            if (rememberMe) {
                request.addParameter(REMEMBER_ME, "on");
            }
            if (adminPortal) {
                request.addParameter("portal", "admin");
            }
            if (friendGate != null) {
                request.addHeader(FriendGate.HEADER, friendGate);
            }
            return request;
        }).andReturn();
    }

    /** その利用者のその種類の監査ログ（新しい順、50 件まで）。 */
    private List<AuditLog> auditLogs(String username, AuditAction action) {
        return auditLogRepository.search(null, null, username, action, null, null, PageRequest.of(0, 50)).getContent();
    }

    /**
     * 管理者だけに開いている画面と API の一覧。SecurityConfig に ADMIN の規則を足したら、ここにも 1 行足す。
     * 行が欠けると、その規則が既定の authenticated() に落ちても気づけない（以前、一般利用者がチャンネルを削除できた）。
     * 入口（{@code /}）は一般利用者を {@code /my} へ送るので入れない（{@code DeniedResponses} で確かめる）。
     */
    static Stream<Arguments> adminOnlyRequests() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/index.html"),
                Arguments.of(HttpMethod.GET, "/tables.html"),
                Arguments.of(HttpMethod.GET, "/invitations.html"),
                Arguments.of(HttpMethod.GET, "/logs.html"),
                Arguments.of(HttpMethod.GET, "/audit.html"),
                Arguments.of(HttpMethod.GET, "/users.html"),
                Arguments.of(HttpMethod.GET, "/channels.html"),
                Arguments.of(HttpMethod.GET, "/notifications.html"),
                Arguments.of(HttpMethod.GET, "/recordings.html"),
                Arguments.of(HttpMethod.GET, "/player.html"),
                Arguments.of(HttpMethod.GET, "/h2-console/"),
                Arguments.of(HttpMethod.GET, "/api/admin/tables"),
                Arguments.of(HttpMethod.PUT, "/api/admin/tables/APP_USERS/1"),
                Arguments.of(HttpMethod.GET, "/api/admin/invitations"),
                Arguments.of(HttpMethod.POST, "/api/admin/invitations"),
                Arguments.of(HttpMethod.DELETE, "/api/admin/invitations/1"),
                Arguments.of(HttpMethod.GET, "/api/logs/system"),
                Arguments.of(HttpMethod.GET, "/api/settings"),
                Arguments.of(HttpMethod.PUT, "/api/settings"),
                Arguments.of(HttpMethod.POST, "/api/settings/directories/pick"),
                Arguments.of(HttpMethod.GET, "/api/audit-logs"),
                Arguments.of(HttpMethod.GET, "/api/admin/users"),
                Arguments.of(HttpMethod.POST, "/api/admin/users/1/disable"),
                Arguments.of(HttpMethod.POST, "/api/admin/users/1/password-reset"),
                Arguments.of(HttpMethod.DELETE, "/api/admin/users/1"),
                Arguments.of(HttpMethod.GET, "/api/channels"),
                Arguments.of(HttpMethod.POST, "/api/channels"),
                Arguments.of(HttpMethod.DELETE, "/api/channels/1"),
                Arguments.of(HttpMethod.GET, "/api/notifications"),
                Arguments.of(HttpMethod.GET, "/api/recordings"),
                Arguments.of(HttpMethod.DELETE, "/api/recordings/1"),
                Arguments.of(HttpMethod.GET, "/api/recordings/orphaned/preview"),
                Arguments.of(HttpMethod.DELETE, "/api/recordings/orphaned/confirmed"),
                Arguments.of(HttpMethod.POST, "/api/downloads"),
                Arguments.of(HttpMethod.POST, "/api/monitor/check"),
                Arguments.of(HttpMethod.GET, "/api/dashboard"),
                Arguments.of(HttpMethod.POST, "/api/discover/run"));
    }

    @Nested
    @DisplayName("未認証アクセス")
    class Unauthenticated {

        @Test
        @DisplayName("正常系：ログイン画面・静的資材は認証なしで200になる")
        void testMethod01() throws Exception {
            mockMvc.perform(get("/userLogin.html")).andExpect(status().isOk());
            mockMvc.perform(get("/css/style.css")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("異常系：保護対象の画面に認証なしでアクセスすると302でログイン画面へ転送される")
        void testMethod02() throws Exception {
            mockMvc.perform(get("/channels.html"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", containsString("/userLogin.html")));
        }

        @Test
        @DisplayName("異常系：録画ファイルの配信も認証なしでは302になる")
        void testMethod03() throws Exception {
            mockMvc.perform(get("/recordings/dummy-channel/dummy-video.mp4"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", containsString("/userLogin.html")));
        }
    }

    @Nested
    @DisplayName("ログイン・ログアウト")
    class LoginLogout {

        @Test
        @DisplayName("正常系：正しい認証情報でログインするとADMINは/index.htmlへ遷移する")
        void testMethod01() throws Exception {
            RequestBuilder form = SecurityMockMvcRequestBuilders.formLogin("/api/auth/login")
                    .user(ADMIN_USERNAME).password(ADMIN_PASSWORD);
            mockMvc.perform(context -> {
                        var request = form.buildRequest(context);
                        // 管理者は管理者用のログイン画面（portal=admin）からしかログインできない
                        request.addParameter("portal", "admin");
                        return request;
                    })
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", "/index.html"));
        }

        @Test
        @DisplayName("正常系：USER権限でログインすると/myへ遷移する（/index.htmlも/channels.htmlもADMIN専用のため）")
        void testMethod02() throws Exception {
            mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/api/auth/login")
                            .user(NORMAL_USERNAME).password(NORMAL_PASSWORD))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", "/my"));
        }

        @Test
        @DisplayName("異常系：誤ったパスワードでログインすると?error付きでログイン画面へ戻る")
        void testMethod03() throws Exception {
            mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/api/auth/login")
                            .user(ADMIN_USERNAME).password("wrong-password"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", "/userLogin.html?error"));
        }

        @Test
        @DisplayName("正常系：ログアウトすると?logout付きでログイン画面へ転送される")
        void testMethod04() throws Exception {
            mockMvc.perform(SecurityMockMvcRequestBuilders.logout("/api/auth/logout"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", "/userLogin.html?logout"));
        }
    }

    @Nested
    @DisplayName("ログイン試行制限")
    class LoginAttemptLimit {
        @Test
        @DisplayName("異常系：実在・非実在の利用者を同じ429応答で拒否する")
        void testMethod01() throws Exception {
            String known = "limit-test-existing";
            appUserRepository.save(new AppUser(known, passwordEncoder.encode("correct-password"), Role.USER));

            for (int index = 0; index < 5; index++) {
                assertThat(failedLogin(known, "198.51.100.240").getStatus()).isEqualTo(302);
                assertThat(failedLogin("limit-test-missing", "198.51.100.241").getStatus()).isEqualTo(302);
            }

            MockHttpServletResponse knownResponse = failedLogin(known, "198.51.100.240");
            MockHttpServletResponse missingResponse = failedLogin("limit-test-missing", "198.51.100.241");
            assertThat(knownResponse.getStatus()).isEqualTo(429);
            assertThat(missingResponse.getStatus()).isEqualTo(429);
            assertThat(missingResponse.getHeader("Retry-After"))
                    .isEqualTo(knownResponse.getHeader("Retry-After"));
            assertThat(missingResponse.getContentAsString())
                    .isEqualTo(knownResponse.getContentAsString());
        }

        private MockHttpServletResponse failedLogin(String username, String address) throws Exception {
            RequestBuilder form = SecurityMockMvcRequestBuilders.formLogin("/api/auth/login")
                    .user(username).password("wrong-password");
            return mockMvc.perform(context -> {
                        var request = form.buildRequest(context);
                        // MockMvc は既定でパスを pathInfo に置くが、実際のサーブレットでは servletPath に入る。
                        request.setServletPath("/api/auth/login");
                        request.setPathInfo(null);
                        request.setRemoteAddr(address);
                        request.addHeader("X-Forwarded-For", "203.0.113.99");
                        return request;
                    })
                    .andReturn().getResponse();
        }
    }

    @Nested
    @DisplayName("権限による制御")
    class RoleBasedAccess {

        @Test
        @DisplayName("正常系：ADMINはDB管理画面のAPIにアクセスできる")
        void testMethod01() throws Exception {
            mockMvc.perform(get("/api/admin/tables")
                            .with(SecurityMockMvcRequestPostProcessors.user(ADMIN_USERNAME).roles("ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("異常系：USER権限でDB管理画面のAPIにアクセスすると403になる")
        void testMethod02() throws Exception {
            mockMvc.perform(get("/api/admin/tables")
                            .with(SecurityMockMvcRequestPostProcessors.user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系：USER権限は自分の購読画面にはアクセスできる")
        void testMethod03() throws Exception {
            mockMvc.perform(get("/my/channels")
                            .with(SecurityMockMvcRequestPostProcessors.user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("異常系：USER権限では管理者のチャンネル管理画面にアクセスできない")
        void testMethod04() throws Exception {
            // ここが開いていると、一般利用者がチャンネル本体を削除でき、
            // 通知履歴・録画履歴まで連鎖削除できてしまう
            mockMvc.perform(get("/channels.html")
                            .with(SecurityMockMvcRequestPostProcessors.user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系：USER権限は購読していないチャンネル・チャンネルに紐づかない録画ファイルでも403にならない")
        void testMethod05() throws Exception {
            // 利用者のアーカイブは全録画を出すので、ファイルも購読に関係なく開ける（#419）。
            // この利用者は何も購読していない。認可を通ればファイルが無いので 404、止められれば 403 になる
            mockMvc.perform(get("/recordings/UCnotsubscribed/video001.mp4")
                            .with(SecurityMockMvcRequestPostProcessors.user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isNotFound());
            // URL を貼って取得した録画の置き場（VideoDownloadService.UNLINKED_DIRECTORY）
            mockMvc.perform(get("/recordings/downloads/video002.mp4")
                            .with(SecurityMockMvcRequestPostProcessors.user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isNotFound());
        }
    }
    @Nested
    class OnlineVideos {
        @Test @DisplayName("正常系：認証済み利用者は購読範囲の動画APIと画面へアクセスできる")
        void testMethod01() throws Exception {
            mockMvc.perform(get("/api/videos").with(SecurityMockMvcRequestPostProcessors.user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/videos.html").with(SecurityMockMvcRequestPostProcessors.user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", containsString("frame-src https://www.youtube.com https://www.youtube-nocookie.com https://player.twitch.tv")));
        }
        @Test @DisplayName("異常系：動画未取得は404で条件不正は400を返す")
        void testMethod03() throws Exception {
            mockMvc.perform(get("/api/videos/YOUTUBE_missing/thumbnail").with(SecurityMockMvcRequestPostProcessors.user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/videos?page=-1").with(SecurityMockMvcRequestPostProcessors.user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isBadRequest());
        }
        @Test @DisplayName("異常系：未認証の動画APIと画像はリダイレクトせず401を返す")
        void testMethod02() throws Exception {
            mockMvc.perform(get("/api/videos")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/videos/YOUTUBE_abcdefghijk/thumbnail")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("認可の規則の一覧")
    class AuthorizationRules {

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.example.monitor.security.SecurityConfigTest#adminOnlyRequests")
        @DisplayName("異常系：一般利用者は管理者専用の画面とAPIで403になる")
        void testMethod01(HttpMethod method, String path) throws Exception {
            // csrf() を付けないと、POST・PUT・DELETE は CSRF の検証で先に 403 になり、認可の規則を試したことにならない
            mockMvc.perform(request(method, path).with(user("t04-rules-user").roles("USER")).with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.example.monitor.security.SecurityConfigTest#adminOnlyRequests")
        @DisplayName("異常系：未ログインでは管理者専用のAPIは401、画面はログイン画面への転送になる")
        void testMethod02(HttpMethod method, String path) throws Exception {
            ResultActions result = mockMvc.perform(request(method, path).with(csrf()));
            if (path.startsWith("/api/")) {
                result.andExpect(status().isUnauthorized());
            } else {
                // 転送先（利用者用か管理者用か）は比べない。MockMvc は servletPath が空なので、本番と違って常に /userLogin.html になる
                result.andExpect(status().is3xxRedirection())
                        .andExpect(header().string("Location", containsString("Login.html")));
            }
        }

        @ParameterizedTest(name = "GET {0}")
        @ValueSource(strings = {"/register.html", "/password-reset.html", "/adminLogin.html", "/api/health",
                "/api/registration?token=t04-no-such-token", "/api/password-reset?token=t04-no-such-token"})
        @DisplayName("正常系：招待からの登録・パスワードの再設定・管理者のログイン画面・巡回の生存は未ログインでも200になる")
        void testMethod03(String path) throws Exception {
            // token の API は使えない token にも {"usable":false,...} の 200 を返す。
            // /api/health はテストの設定で巡回が無効なので DISABLED の 200 を返す
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }

        @ParameterizedTest(name = "GET {0}")
        @ValueSource(strings = {"/my", "/api/my/channels", "/api/platforms"})
        @DisplayName("正常系：一般利用者は自分の画面と利用者のAPIを開ける")
        void testMethod04(String path) throws Exception {
            // /api/my/channels は DB から利用者を引くので、DB にいる名前でないと 500 になる
            mockMvc.perform(get(path).with(user(NORMAL_USERNAME).roles("USER")))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("ログイン画面と役割の突き合わせ")
    class PortalCheck {

        @Test
        @DisplayName("異常系：管理者が利用者用の画面から正しいパスワードでログインしても?error付きで戻される")
        void testMethod01() throws Exception {
            MvcResult result = login(ADMIN_USERNAME, ADMIN_PASSWORD, false, false);

            assertThat(result.getResponse().getStatus()).isEqualTo(302);
            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/userLogin.html?error");
        }

        @Test
        @DisplayName("異常系：一般利用者が管理者用の画面から正しいパスワードでログインしても?error付きで戻される")
        void testMethod02() throws Exception {
            MvcResult result = login(NORMAL_USERNAME, NORMAL_PASSWORD, false, true);

            assertThat(result.getResponse().getStatus()).isEqualTo(302);
            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/adminLogin.html?error");
        }
    }

    @Nested
    @DisplayName("友人用の入口（関所）")
    class FriendGateAccess {

        /** 自動ログインの記録（LOGIN_SUCCESS のうち補足が自動ログインのもの）の件数。 */
        private long autoLogins(String username) {
            return auditLogs(username, AuditAction.LOGIN_SUCCESS).stream()
                    .filter(log -> AppRememberMeServices.AUTO_LOGIN_DETAIL.equals(log.getDetail()))
                    .count();
        }

        @ParameterizedTest
        @ValueSource(strings = {"1", "", "admin", "0"})
        @DisplayName("異常系：関所から来た管理者はportal=adminでも正しいパスワードでログインできず、利用者のログイン画面に?error付きで戻される")
        void testMethod01(String headerValue) throws Exception {
            createUser("t861-gate-admin-login", Role.ADMIN);
            int failuresBefore = auditLogs("t861-gate-admin-login", AuditAction.LOGIN_FAILURE).size();

            MvcResult result = login("t861-gate-admin-login", T04_PASSWORD, true, true, headerValue);

            assertThat(result.getResponse().getStatus()).isEqualTo(302);
            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/userLogin.html?error");
            // 「ログインしたまま」を選んでいても Cookie は出ない（失敗のときに付くのは消すための Max-Age=0 だけ）
            assertThat(result.getResponse().getCookie(REMEMBER_ME)).extracting(Cookie::getMaxAge).isEqualTo(0);
            assertThat(auditLogs("t861-gate-admin-login", AuditAction.LOGIN_FAILURE)).hasSize(failuresBefore + 1);
        }

        @Test
        @DisplayName("異常系：関所から来た管理者はportalを送らなくてもログインできない")
        void testMethod02() throws Exception {
            createUser("t861-gate-admin-noportal", Role.ADMIN);

            MvcResult result = login("t861-gate-admin-noportal", T04_PASSWORD, false, false, "1");

            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/userLogin.html?error");
        }

        @Test
        @DisplayName("正常系：一般利用者は関所からログインでき、利用者のAPIを使える")
        void testMethod03() throws Exception {
            createUser("t861-gate-user", Role.USER);

            MvcResult result = login("t861-gate-user", T04_PASSWORD, false, false, "1");

            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/my");
            MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
            mockMvc.perform(get("/api/my/channels").session(session).header(FriendGate.HEADER, "1"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正常系：一般利用者の「ログインしたまま」のCookieは関所でも使える")
        void testMethod04() throws Exception {
            createUser("t861-gate-user-rm", Role.USER);
            Cookie cookie = login("t861-gate-user-rm", T04_PASSWORD, true, false, "1").getResponse().getCookie(REMEMBER_ME);
            assertThat(cookie).isNotNull();

            mockMvc.perform(get("/api/my/channels").cookie(cookie).header(FriendGate.HEADER, "1"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("異常系：管理者の「ログインしたまま」のCookieは関所では401になって消され、自動ログインの記録も残らない")
        void testMethod05() throws Exception {
            createUser("t861-gate-admin-rm", Role.ADMIN);
            Cookie cookie = login("t861-gate-admin-rm", T04_PASSWORD, true, true).getResponse().getCookie(REMEMBER_ME);
            assertThat(cookie).isNotNull();
            long autoLoginsBefore = autoLogins("t861-gate-admin-rm");

            MvcResult denied = mockMvc.perform(get("/api/admin/tables").cookie(cookie).header(FriendGate.HEADER, "1"))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            Cookie cancelled = denied.getResponse().getCookie(REMEMBER_ME);
            assertThat(cancelled).isNotNull();
            assertThat(cancelled.getMaxAge()).isZero();
            // 利用者の画面でも管理者としては入れない（ログイン画面へ）
            mockMvc.perform(get("/my").cookie(cookie).header(FriendGate.HEADER, "1"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/userLogin.html"));
            assertThat(autoLogins("t861-gate-admin-rm")).isEqualTo(autoLoginsBefore);

            // 関所を通らない要求（管理者の入口）では今までどおり使える
            mockMvc.perform(get("/api/admin/tables").cookie(cookie)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("異常系：管理者のセッションは関所では401になり、無効になる")
        void testMethod06() throws Exception {
            createUser("t861-gate-admin-session", Role.ADMIN);
            MvcResult loggedIn = login("t861-gate-admin-session", T04_PASSWORD, false, true);
            MockHttpSession session = (MockHttpSession) loggedIn.getRequest().getSession(false);
            mockMvc.perform(get("/api/admin/tables").session(session)).andExpect(status().isOk());

            mockMvc.perform(get("/api/admin/tables").session(session).header(FriendGate.HEADER, "1"))
                    .andExpect(status().isUnauthorized());

            assertThat(session.isInvalid()).isTrue();
        }

        @Test
        @DisplayName("異常系：管理者のセッションで関所から画面を開くと、利用者のログイン画面へ戻される")
        void testMethod07() throws Exception {
            createUser("t861-gate-admin-page", Role.ADMIN);
            MvcResult loggedIn = login("t861-gate-admin-page", T04_PASSWORD, false, true);
            MockHttpSession session = (MockHttpSession) loggedIn.getRequest().getSession(false);

            mockMvc.perform(get("/my").session(session).header(FriendGate.HEADER, "1"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/userLogin.html"));

            assertThat(session.isInvalid()).isTrue();
        }
    }

    @Nested
    @DisplayName("ログインしたままにする（remember-me）")
    class RememberMe {

        /** 自分のパスワードを変える要求の本文。 */
        private static final String CHANGE_PASSWORD_BODY =
                "{\"currentPassword\":\"t04-password-1\",\"newPassword\":\"t04-password-2\"}";

        @Test
        @DisplayName("正常系：チェックを付けてログインすると30日のCookieが発行され、そのCookieだけで次の要求に入れる")
        void testMethod01() throws Exception {
            createUser("t04-rm-basic", Role.USER);

            MvcResult result = login("t04-rm-basic", T04_PASSWORD, true, false);

            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/my");
            Cookie cookie = result.getResponse().getCookie(REMEMBER_ME);
            assertThat(cookie).isNotNull();
            assertThat(cookie.getMaxAge()).isEqualTo(REMEMBER_ME_SECONDS);
            assertThat(cookie.isHttpOnly()).isTrue();
            // テストの application.yml は server.servlet.session.cookie.secure を書いていないので、既定の true にそろう（#535）
            assertThat(cookie.getSecure()).isTrue();
            mockMvc.perform(get("/api/my/channels").cookie(cookie)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("正常系：チェックを付けなければCookieは発行されない")
        void testMethod02() throws Exception {
            createUser("t04-rm-off", Role.USER);

            MvcResult result = login("t04-rm-off", T04_PASSWORD, false, false);

            assertThat(result.getResponse().getCookie(REMEMBER_ME)).isNull();
        }

        @Test
        @DisplayName("正常系：Cookieでの自動ログインは監査ログと最終ログイン時刻に残る")
        void testMethod03() throws Exception {
            AppUser user = createUser("t04-rm-audit", Role.USER);
            Cookie cookie = login("t04-rm-audit", T04_PASSWORD, true, false).getResponse().getCookie(REMEMBER_ME);
            // フォームのログインの時刻と比べると同じ時刻になって比べられないことがあるので、古い値にしておく
            LocalDateTime old = LocalDateTime.of(2000, 1, 1, 0, 0);
            appUserRepository.updateLastLoginAt(user.getId(), old);

            mockMvc.perform(get("/api/my/channels").cookie(cookie)).andExpect(status().isOk());

            assertThat(appUserRepository.findByUsername("t04-rm-audit").orElseThrow().getLastLoginAt()).isAfter(old);
            assertThat(auditLogs("t04-rm-audit", AuditAction.LOGIN_SUCCESS))
                    .extracting(AuditLog::getDetail)
                    .contains(AppRememberMeServices.AUTO_LOGIN_DETAIL);
        }

        @Test
        @DisplayName("異常系：パスワードが変わると、それより前に発行したCookieでは入れない")
        void testMethod04() throws Exception {
            AppUser user = createUser("t04-rm-changed", Role.USER);
            Cookie cookie = login("t04-rm-changed", T04_PASSWORD, true, false).getResponse().getCookie(REMEMBER_ME);

            // 管理者の再設定や別の端末での変更と同じ結果
            appUserRepository.updatePassword(user.getId(), passwordEncoder.encode("t04-password-2"),
                    LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS));

            mockMvc.perform(get("/api/my/channels").cookie(cookie)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("異常系：無効化された利用者はCookieで入れない")
        void testMethod05() throws Exception {
            createUser("t04-rm-disabled", Role.USER);
            Cookie cookie = login("t04-rm-disabled", T04_PASSWORD, true, false).getResponse().getCookie(REMEMBER_ME);

            // disableUser はトランザクションを持たないので、テストからは呼ばない
            AppUser u = appUserRepository.findByUsername("t04-rm-disabled").orElseThrow();
            u.setEnabled(false);
            appUserRepository.save(u);

            mockMvc.perform(get("/api/my/channels").cookie(cookie)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系：自分でパスワードを変えると、この端末のCookieだけが作り直され、セッションも続く")
        void testMethod06() throws Exception {
            createUser("t04-rm-own", Role.USER);
            MvcResult loggedIn = login("t04-rm-own", T04_PASSWORD, true, false);
            MockHttpSession session = (MockHttpSession) loggedIn.getRequest().getSession(false);
            Cookie oldCookie = loggedIn.getResponse().getCookie(REMEMBER_ME);

            MvcResult changed = mockMvc.perform(put("/api/my/password").session(session).cookie(oldCookie).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(CHANGE_PASSWORD_BODY))
                    .andExpect(status().isNoContent())
                    .andReturn();

            Cookie newCookie = changed.getResponse().getCookie(REMEMBER_ME);
            assertThat(newCookie).isNotNull();
            assertThat(newCookie.getValue()).isNotEqualTo(oldCookie.getValue());
            assertThat(newCookie.getMaxAge()).isEqualTo(REMEMBER_ME_SECONDS);
            // 変更した本人のセッションは続く（変更時刻をミリ秒に切り詰めていないと、H2 の丸め次第で落ちることがある）
            mockMvc.perform(get("/api/my/channels").session(session)).andExpect(status().isOk());
            mockMvc.perform(get("/api/my/channels").cookie(oldCookie)).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/my/channels").cookie(newCookie)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("正常系：Cookieを持たない端末でパスワードを変えても、Cookieは作られずセッションは続く")
        void testMethod07() throws Exception {
            createUser("t04-rm-none", Role.USER);
            MvcResult loggedIn = login("t04-rm-none", T04_PASSWORD, false, false);
            MockHttpSession session = (MockHttpSession) loggedIn.getRequest().getSession(false);

            MvcResult changed = mockMvc.perform(put("/api/my/password").session(session).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(CHANGE_PASSWORD_BODY))
                    .andExpect(status().isNoContent())
                    .andReturn();

            assertThat(changed.getResponse().getCookie(REMEMBER_ME)).isNull();
            mockMvc.perform(get("/api/my/channels").session(session)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("異常系：Cookieでログインした一般利用者が管理者の画面とAPIを開くと403になり、監査ログに残る")
        void testMethod08() throws Exception {
            createUser("t04-rm-denied", Role.USER);
            Cookie cookie = login("t04-rm-denied", T04_PASSWORD, true, false).getResponse().getCookie(REMEMBER_ME);

            mockMvc.perform(get("/api/admin/users").cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(FORBIDDEN_MESSAGE));
            // ログイン画面への転送ではなく 403
            mockMvc.perform(get("/users.html").cookie(cookie)).andExpect(status().isForbidden());

            assertThat(auditLogs("t04-rm-denied", AuditAction.ACCESS_DENIED))
                    .extracting(AuditLog::getTargetId)
                    .contains("/api/admin/users", "/users.html");
        }
    }

    @Nested
    @DisplayName("拒否の応答と監査ログ")
    class DeniedResponses {

        @Test
        @DisplayName("異常系：ログイン中にCSRFトークン無しで送ると403になり、監査ログにcsrf=trueで残る")
        void testMethod01() throws Exception {
            mockMvc.perform(post("/api/my/channels").with(user("t04-csrf-user").roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(FORBIDDEN_MESSAGE));

            assertThat(auditLogs("t04-csrf-user", AuditAction.ACCESS_DENIED))
                    .extracting(AuditLog::getTargetId, AuditLog::getDetail)
                    .contains(tuple("/api/my/channels", "method=POST, csrf=true"));
        }

        @Test
        @DisplayName("異常系：未ログインでCSRFトークン無しで送ると401になる")
        void testMethod02() throws Exception {
            // セッション切れの POST は CSRF の検証で先に落ちるが、それでも 401 にする（RequestAuthenticationHandler.handle()）
            mockMvc.perform(post("/api/my/channels"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("ログインし直してください"));
        }

        @Test
        @DisplayName("異常系：一般利用者が管理者の画面を開いた403も監査ログに残る")
        void testMethod03() throws Exception {
            mockMvc.perform(get("/tables.html").with(user("t04-page-user").roles("USER")))
                    .andExpect(status().isForbidden());

            assertThat(auditLogs("t04-page-user", AuditAction.ACCESS_DENIED))
                    .extracting(AuditLog::getTargetId, AuditLog::getDetail)
                    .contains(tuple("/tables.html", "method=GET"));
        }

        @Test
        @DisplayName("正常系：一般利用者がサイトの入口（/）を開くと/myへ送られ、監査ログに残らない")
        void testMethod04() throws Exception {
            mockMvc.perform(get("/").with(user("t04-root-user").roles("USER")))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/my"));

            assertThat(auditLogs("t04-root-user", AuditAction.ACCESS_DENIED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("招待と再設定のリンク（実際のDBで1回限り）")
    class TokenLinks {

        @Test
        @DisplayName("正常系：招待からの登録は1回だけ通り、作られるのは一般利用者で、招待に利用者名が残る")
        void testMethod01() throws Exception {
            Invitation invitation = new Invitation();
            invitation.setToken("t04-invite-token");
            invitation.setExpiresAt(LocalDateTime.now().plusDays(1));
            invitationRepository.save(invitation);

            mockMvc.perform(post("/api/registration").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"t04-invite-token\",\"username\":\"t04-invited\",\"password\":\"t04-password-1\"}"))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post("/api/registration").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"t04-invite-token\",\"username\":\"t04-invited-2\",\"password\":\"t04-password-1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("この招待リンクは既に使われています。登録済みのアカウントでログインしてください。"));

            assertThat(appUserRepository.findByUsername("t04-invited").orElseThrow().getRole()).isEqualTo(Role.USER);
            assertThat(appUserRepository.findByUsername("t04-invited-2")).isEmpty();
            Invitation accepted = invitationRepository.findByToken("t04-invite-token").orElseThrow();
            assertThat(accepted.getAcceptedUsername()).isEqualTo("t04-invited");
            assertThat(accepted.getAcceptedAt()).isNotNull();
        }

        @Test
        @DisplayName("正常系：管理者が発行した再設定リンクは1回だけ使え、使うとリンクが消えて新しいパスワードでログインできる")
        void testMethod02() throws Exception {
            AppUser target = createUser("t04-reset", Role.USER);
            String body = mockMvc.perform(post("/api/admin/users/{id}/password-reset", target.getId())
                            .with(user(ADMIN_USERNAME).roles("ADMIN")).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String token = JsonPath.read(body, "$.token");
            String resetBody = "{\"token\":\"" + token + "\",\"password\":\"t04-password-2\"}";

            mockMvc.perform(post("/api/password-reset").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post("/api/password-reset").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(
                            "このリンクは使用済みか、新しいリンクが発行されたため使えません。管理者に再発行を依頼してください。"));

            AppUser reloaded = appUserRepository.findByUsername("t04-reset").orElseThrow();
            assertThat(reloaded.getPasswordResetToken()).isNull();
            assertThat(reloaded.getPasswordResetExpiresAt()).isNull();
            assertThat(passwordEncoder.matches("t04-password-2", reloaded.getPasswordHash())).isTrue();
            assertThat(login("t04-reset", "t04-password-2", false, false).getResponse().getHeader("Location"))
                    .isEqualTo("/my");
        }
    }

}
