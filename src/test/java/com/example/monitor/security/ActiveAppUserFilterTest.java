package com.example.monitor.security;

import com.example.monitor.entity.AppUser;
import com.example.monitor.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ActiveAppUserFilter")
class ActiveAppUserFilterTest {
    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final ActiveAppUserFilter filter = new ActiveAppUserFilter(repository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("doFilterInternal()")
    class DoFilterInternal {
        @Test
        @DisplayName("異常系：同名の別IDが有効でも削除されたIDのセッションはAPIで401になる")
        void testMethod01() throws Exception {
            authenticate(10L, "same-name");
            when(repository.existsByIdAndEnabledTrue(10L)).thenReturn(false);
            MockHttpServletRequest request = request("/api/videos");
            MockHttpSession session = new MockHttpSession();
            request.setSession(session);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicInteger calls = new AtomicInteger();

            filter.doFilter(request, response, countingChain(calls));

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("ログインし直してください");
            assertThat(session.isInvalid()).isTrue();
            assertThat(calls).hasValue(0);
        }

        @Test
        @DisplayName("異常系：無効化された利用者の画面要求はログイン画面へ戻す")
        void testMethod02() throws Exception {
            authenticate(11L, "disabled");
            when(repository.existsByIdAndEnabledTrue(11L)).thenReturn(false);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("/my-channels.html"), response, countingChain(new AtomicInteger()));

            assertThat(response.getStatus()).isEqualTo(302);
            assertThat(response.getRedirectedUrl()).isEqualTo("/login.html");
        }

        @Test
        @DisplayName("正常系：同じIDの有効な利用者は後続処理へ進める")
        void testMethod03() throws Exception {
            authenticate(12L, "active");
            when(repository.existsByIdAndEnabledTrue(12L)).thenReturn(true);
            AtomicInteger calls = new AtomicInteger();

            filter.doFilter(request("/api/videos"), new MockHttpServletResponse(), countingChain(calls));

            assertThat(calls).hasValue(1);
        }
    }

    private static void authenticate(Long id, String name) {
        AppUser user = new AppUser(name, "hash", AppUser.Role.USER);
        user.setId(id);
        AuthenticatedAppUser principal = new AuthenticatedAppUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private static FilterChain countingChain(AtomicInteger calls) {
        return (request, response) -> calls.incrementAndGet();
    }
}
