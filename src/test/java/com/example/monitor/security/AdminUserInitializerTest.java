package com.example.monitor.security;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.AdminProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AppUser.Role;
import com.example.monitor.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserInitializer")
class AdminUserInitializerTest {

    @Mock
    private AppUserRepository appUserRepository;

    /**
     * ハッシュ化されていることを実際に確認したいため、モックではなく実物を使う
     * （「平文がDBに無いこと」を検証するには本物のBCrypt実装が要る）。
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private MonitorProperties propertiesWithAdmin(String username, String password) {
        return new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties("", ""),
                new DiscordProperties(""),
                new RecordingProperties("recordings", 0),
                new AdminProperties(username, password));
    }

    @Nested
    @DisplayName("run()")
    class Run {

        @Test
        @DisplayName("正常系：管理者が1人もいなければパスワードをハッシュ化して作成する")
        void testMethod01() {
            when(appUserRepository.existsByRole(Role.ADMIN)).thenReturn(false);
            AdminUserInitializer initializer = new AdminUserInitializer(
                    appUserRepository, passwordEncoder, propertiesWithAdmin("admin", "raw-password"));

            initializer.run();

            ArgumentCaptor<AppUser> savedUser = ArgumentCaptor.forClass(AppUser.class);
            verify(appUserRepository).save(savedUser.capture());
            AppUser created = savedUser.getValue();
            assertThat(created.getUsername()).isEqualTo("admin");
            assertThat(created.getRole()).isEqualTo(Role.ADMIN);
            // 平文がそのまま保存されていないこと、かつ正しくハッシュ化されていることの両方を確認する
            assertThat(created.getPasswordHash()).isNotEqualTo("raw-password");
            assertThat(passwordEncoder.matches("raw-password", created.getPasswordHash())).isTrue();
        }

        @Test
        @DisplayName("正常系：既に管理者がいれば何もしない")
        void testMethod02() {
            when(appUserRepository.existsByRole(Role.ADMIN)).thenReturn(true);
            AdminUserInitializer initializer = new AdminUserInitializer(
                    appUserRepository, passwordEncoder, propertiesWithAdmin("admin", "raw-password"));

            initializer.run();

            verify(appUserRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("異常系：管理者がおらずパスワードも未設定なら作成せずスキップする")
        void testMethod03() {
            when(appUserRepository.existsByRole(Role.ADMIN)).thenReturn(false);
            AdminUserInitializer initializer = new AdminUserInitializer(
                    appUserRepository, passwordEncoder, propertiesWithAdmin("admin", ""));

            initializer.run();

            verify(appUserRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }
}
