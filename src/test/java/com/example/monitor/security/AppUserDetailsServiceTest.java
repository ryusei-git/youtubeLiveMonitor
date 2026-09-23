package com.example.monitor.security;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AppUser.Role;
import com.example.monitor.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppUserDetailsService")
class AppUserDetailsServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    @InjectMocks
    private AppUserDetailsService appUserDetailsService;

    @Nested
    @DisplayName("loadUserByUsername()")
    class LoadUserByUsername {

        @Test
        @DisplayName("正常系：ADMINの利用者はROLE_ADMIN権限を持つ有効な利用者として返る")
        void testMethod01() {
            AppUser appUser = new AppUser("admin", "hashed-value", Role.ADMIN);
            when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(appUser));

            UserDetails userDetails = appUserDetailsService.loadUserByUsername("admin");

            assertThat(userDetails.getUsername()).isEqualTo("admin");
            assertThat(userDetails.getPassword()).isEqualTo("hashed-value");
            assertThat(userDetails.isEnabled()).isTrue();
            assertThat(userDetails.getAuthorities())
                    .extracting(Object::toString)
                    .containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("正常系：無効化された利用者はdisabledがtrueになる")
        void testMethod02() {
            AppUser appUser = new AppUser("disabled-user", "hashed-value", Role.USER);
            appUser.setEnabled(false);
            when(appUserRepository.findByUsername("disabled-user")).thenReturn(Optional.of(appUser));

            UserDetails userDetails = appUserDetailsService.loadUserByUsername("disabled-user");

            assertThat(userDetails.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("異常系：該当する利用者が存在しない場合はUsernameNotFoundExceptionが発生する")
        void testMethod03() {
            when(appUserRepository.findByUsername("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> appUserDetailsService.loadUserByUsername("unknown"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }
    }
}
