package com.example.monitor.entity;

import com.example.monitor.entity.AppUser.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AppUser")
class AppUserTest {

    @Nested
    @DisplayName("AppUser(String, String, Role)")
    class ConstructorTest {

        @Test
        @DisplayName("正常系：既定で有効な利用者として組み立てられる")
        void testMethod01() {
            AppUser user = new AppUser("admin", "hashed-value", Role.ADMIN);

            assertThat(user.getUsername()).isEqualTo("admin");
            assertThat(user.getPasswordHash()).isEqualTo("hashed-value");
            assertThat(user.getRole()).isEqualTo(Role.ADMIN);
            assertThat(user.isEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("applyCreatedAtOnInsert()")
    class ApplyCreatedAtOnInsert {

        @Test
        @DisplayName("正常系：呼び出し時点の時刻がcreatedAtに設定される")
        void testMethod01() {
            AppUser user = new AppUser();
            LocalDateTime before = LocalDateTime.now();

            user.applyCreatedAtOnInsert();

            LocalDateTime after = LocalDateTime.now();
            assertThat(user.getCreatedAt()).isBetween(before, after);
        }
    }
}
