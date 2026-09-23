package com.example.monitor.security;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AppUser.Role;
import com.example.monitor.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Nested
    @DisplayName("未認証アクセス")
    class Unauthenticated {

        @Test
        @DisplayName("正常系：ログイン画面・静的資材は認証なしで200になる")
        void testMethod01() throws Exception {
            mockMvc.perform(get("/login.html")).andExpect(status().isOk());
            mockMvc.perform(get("/css/style.css")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("異常系：保護対象の画面に認証なしでアクセスすると302でログイン画面へ転送される")
        void testMethod02() throws Exception {
            mockMvc.perform(get("/channels.html"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", containsString("/login.html")));
        }

        @Test
        @DisplayName("異常系：録画ファイルの配信も認証なしでは302になる")
        void testMethod03() throws Exception {
            mockMvc.perform(get("/recordings/dummy-channel/dummy-video.mp4"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", containsString("/login.html")));
        }
    }

    @Nested
    @DisplayName("ログイン・ログアウト")
    class LoginLogout {

        @Test
        @DisplayName("正常系：正しい認証情報でログインするとADMINは/index.htmlへ遷移する")
        void testMethod01() throws Exception {
            mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/api/auth/login")
                            .user(ADMIN_USERNAME).password(ADMIN_PASSWORD))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", "/index.html"));
        }

        @Test
        @DisplayName("正常系：USER権限でログインすると/my-channels.htmlへ遷移する（/index.htmlも/channels.htmlもADMIN専用のため）")
        void testMethod02() throws Exception {
            mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/api/auth/login")
                            .user(NORMAL_USERNAME).password(NORMAL_PASSWORD))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", "/my-channels.html"));
        }

        @Test
        @DisplayName("異常系：誤ったパスワードでログインすると?error付きでログイン画面へ戻る")
        void testMethod03() throws Exception {
            mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/api/auth/login")
                            .user(ADMIN_USERNAME).password("wrong-password"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", "/login.html?error"));
        }

        @Test
        @DisplayName("正常系：ログアウトすると?logout付きでログイン画面へ転送される")
        void testMethod04() throws Exception {
            mockMvc.perform(SecurityMockMvcRequestBuilders.logout("/api/auth/logout"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", "/login.html?logout"));
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
            mockMvc.perform(get("/my-channels.html")
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
        @Test @DisplayName("異常系：未認証では動画APIも画像も取得できない")
        void testMethod02() throws Exception {
            mockMvc.perform(get("/api/videos")).andExpect(status().is3xxRedirection());
            mockMvc.perform(get("/api/videos/YOUTUBE_abcdefghijk/thumbnail")).andExpect(status().is3xxRedirection());
        }
    }

}
