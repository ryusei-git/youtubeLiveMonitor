package com.example.monitor.service;

import com.example.monitor.entity.AppUser;
import com.example.monitor.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppUserManagementService")
class AppUserManagementServiceTest {
    @Mock AppUserRepository repository;
    @InjectMocks AppUserManagementService service;

    @Nested
    @DisplayName("disable()")
    class Disable {
        @Test
        @DisplayName("異常系：操作者自身が一般利用者でも無効化を拒否する")
        void testMethod01() {
            when(repository.findById(10L)).thenReturn(Optional.of(user("self", AppUser.Role.USER)));

            assertThatThrownBy(() -> service.disable(10L, "self"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("409 CONFLICT");
            verify(repository, never()).disableUser(10L, AppUser.Role.USER);
        }

        @Test
        @DisplayName("異常系：読み込み後に権限が変わった対象を更新せず競合として返す")
        void testMethod02() {
            when(repository.findById(10L)).thenReturn(Optional.of(user("target", AppUser.Role.USER)));
            when(repository.disableUser(10L, AppUser.Role.USER)).thenReturn(0);

            assertThatThrownBy(() -> service.disable(10L, "admin"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("409 CONFLICT");
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {
        @Test
        @DisplayName("異常系：存在しない利用者は404で削除しない")
        void testMethod01() {
            when(repository.findById(10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(10L, "admin"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404 NOT_FOUND");
            verify(repository, never()).deleteUser(10L, AppUser.Role.USER);
        }

        @Test
        @DisplayName("異常系：管理者を削除しない")
        void testMethod02() {
            when(repository.findById(10L)).thenReturn(Optional.of(user("another-admin", AppUser.Role.ADMIN)));

            assertThatThrownBy(() -> service.delete(10L, "admin"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("409 CONFLICT");
            verify(repository, never()).deleteUser(10L, AppUser.Role.USER);
        }
    }

    private static AppUser user(String name, AppUser.Role role) {
        return new AppUser(name, "hash", role);
    }
}
