package com.example.monitor.security;

import com.example.monitor.entity.AppUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuthenticatedAppUser")
class AuthenticatedAppUserTest {
    @Nested
    @DisplayName("AuthenticatedAppUser()")
    class Constructor {
        @Test
        @DisplayName("正常系：セッションの本人確認用IDと権限をログイン時の利用者から保持する")
        void testMethod01() {
            AppUser user = new AppUser("issue25-user", "hash", AppUser.Role.USER);
            user.setId(25L);

            AuthenticatedAppUser principal = new AuthenticatedAppUser(user);

            assertThat(principal.getUserId()).isEqualTo(25L);
            assertThat(principal.getUsername()).isEqualTo("issue25-user");
            assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        }
    }
}
