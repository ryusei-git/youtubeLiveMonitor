package com.example.monitor.controller;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditLog;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.AuditLogRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 実際の認可・CSRF・セッションフィルターとインメモリDBの連動を確認する。 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AppUserManagementController")
class AppUserManagementControllerTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired UserSubscriptionRepository subscriptions;
    @Autowired MonitoredChannelRepository channels;
    @Autowired RecordingRepository recordings;
    @Autowired AuditLogRepository auditLogs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @Nested
    @DisplayName("list()")
    class ListUsers {
        @Test
        @DisplayName("正常系：管理者には表示項目だけを返しパスワードハッシュを含めない")
        void testMethod01() throws Exception {
            AppUser target = createUser("issue25-list");
            String body = mvc.perform(get("/api/admin/users").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.username == 'issue25-list')].id").value(target.getId().intValue()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("passwordHash", target.getPasswordHash());
        }

        @Test
        @DisplayName("異常系：一般利用者は管理APIの一覧を読めない")
        void testMethod02() throws Exception {
            mvc.perform(get("/api/admin/users").with(user("issue25-reader").roles("USER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("disable()")
    class Disable {
        @Test
        @DisplayName("正常系：無効化後は既存セッションの次のAPI要求を401にする")
        void testMethod01() throws Exception {
            AppUser target = createUser("issue25-disable");
            MockHttpSession session = login(target.getUsername());
            mvc.perform(get("/api/videos").session(session)).andExpect(status().isOk());

            mvc.perform(post("/api/admin/users/{id}/disable", target.getId()).with(admin()).with(csrf()))
                    .andExpect(status().isNoContent());

            assertThat(users.findById(target.getId()).orElseThrow().isEnabled()).isFalse();
            mvc.perform(get("/api/videos").session(session)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("異常系：管理者と自分自身の操作を拒否する")
        void testMethod02() throws Exception {
            Long adminId = users.findByUsername("testadmin").orElseThrow().getId();
            mvc.perform(post("/api/admin/users/{id}/disable", adminId).with(admin()).with(csrf()))
                    .andExpect(status().isConflict());
            assertThat(users.findById(adminId).orElseThrow().isEnabled()).isTrue();
        }

        @Test
        @DisplayName("異常系：一般利用者の操作とCSRFトークンのない管理者操作を拒否する")
        void testMethod03() throws Exception {
            AppUser target = createUser("issue25-disable-denied");
            String path = "/api/admin/users/" + target.getId() + "/disable";
            mvc.perform(post(path).with(user("issue25-other").roles("USER")).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).with(admin())).andExpect(status().isForbidden());
            assertThat(users.findById(target.getId()).orElseThrow().isEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {
        @Test
        @DisplayName("正常系：削除後の同名再登録でも旧セッションは失効し購読だけが連鎖削除される")
        void testMethod01() throws Exception {
            AppUser target = createUser("issue25-delete");
            MonitoredChannel channel = channels.save(new MonitoredChannel("UCissue25delete000000000", "保存するチャンネル"));
            UserSubscription subscription = subscriptions.save(UserSubscription.builder()
                    .user(target).channel(channel).build());
            Recording recording = recordings.save(Recording.builder().channel(channel)
                    .videoId("issue25-video").filePath("issue25/video.mp4")
                    .status(Recording.RecordingStatus.COMPLETED).build());
            AuditLog audit = auditLogs.save(AuditLog.builder().userId(target.getId())
                    .username(target.getUsername()).action(AuditAction.USER_DISABLE)
                    .outcome(AuditOutcome.SUCCESS).build());
            MockHttpSession oldSession = login(target.getUsername());

            mvc.perform(delete("/api/admin/users/{id}", target.getId()).with(admin()).with(csrf()))
                    .andExpect(status().isNoContent());
            AppUser replacement = createUser(target.getUsername());

            assertThat(replacement.getId()).isNotEqualTo(target.getId());
            assertThat(users.existsById(target.getId())).isFalse();
            assertThat(subscriptions.existsById(subscription.getId())).isFalse();
            assertThat(channels.existsById(channel.getId())).isTrue();
            assertThat(recordings.existsById(recording.getId())).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from audit_logs where id = ?", Integer.class,
                    audit.getId())).isEqualTo(1);
            mvc.perform(get("/api/videos").session(oldSession)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("異常系：一般利用者・CSRFなし・管理者自身の削除を拒否する")
        void testMethod02() throws Exception {
            AppUser target = createUser("issue25-delete-denied");
            String path = "/api/admin/users/" + target.getId();
            mvc.perform(delete(path).with(user("issue25-other").roles("USER")).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(path).with(admin())).andExpect(status().isForbidden());
            Long adminId = users.findByUsername("testadmin").orElseThrow().getId();
            mvc.perform(delete("/api/admin/users/{id}", adminId).with(admin()).with(csrf()))
                    .andExpect(status().isConflict());
            assertThat(users.existsById(target.getId())).isTrue();
            assertThat(users.existsById(adminId)).isTrue();
        }
    }

    private AppUser createUser(String name) {
        return users.save(new AppUser(name, passwords.encode("issue25-password"), AppUser.Role.USER));
    }

    private MockHttpSession login(String name) throws Exception {
        var result = mvc.perform(formLogin("/api/auth/login").user(name).password("issue25-password"))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("testadmin").roles("ADMIN");
    }
}
