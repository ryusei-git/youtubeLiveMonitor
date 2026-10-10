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
            when(repository.isSessionValid(10L, null)).thenReturn(false);
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
            when(repository.isSessionValid(11L, null)).thenReturn(false);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("/my-channels.html"), response, countingChain(new AtomicInteger()));

            assertThat(response.getStatus()).isEqualTo(302);
            assertThat(response.getRedirectedUrl()).isEqualTo("/userLogin.html");
        }

        @Test
        @DisplayName("正常系：同じIDの有効な利用者は後続処理へ進める")
        void testMethod03() throws Exception {
            authenticate(12L, "active");
            when(repository.isSessionValid(12L, null)).thenReturn(true);
            AtomicInteger calls = new AtomicInteger();

            filter.doFilter(request("/api/videos"), new MockHttpServletResponse(), countingChain(calls));

            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("異常系：関所から来た管理者のセッションは有効でも401になり、後続処理へ進まない")
        void testMethod04() throws Exception {
            authenticate(13L, "admin", AppUser.Role.ADMIN);
            when(repository.isSessionValid(13L, null)).thenReturn(true);
            MockHttpServletRequest request = request("/api/my/channels");
            request.addHeader(FriendGate.HEADER, "1");
            MockHttpSession session = new MockHttpSession();
            request.setSession(session);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicInteger calls = new AtomicInteger();

            filter.doFilter(request, response, countingChain(calls));

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(session.isInvalid()).isTrue();
            assertThat(calls).hasValue(0);
        }

        @Test
        @DisplayName("正常系：関所から来ても一般利用者は後続処理へ進める")
        void testMethod05() throws Exception {
            authenticate(14L, "friend", AppUser.Role.USER);
            when(repository.isSessionValid(14L, null)).thenReturn(true);
            MockHttpServletRequest request = request("/api/my/channels");
            request.addHeader(FriendGate.HEADER, "1");
            AtomicInteger calls = new AtomicInteger();

            filter.doFilter(request, new MockHttpServletResponse(), countingChain(calls));

            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("正常系：関所を通らない管理者は後続処理へ進める")
        void testMethod06() throws Exception {
            authenticate(15L, "admin", AppUser.Role.ADMIN);
            when(repository.isSessionValid(15L, null)).thenReturn(true);
            AtomicInteger calls = new AtomicInteger();

            filter.doFilter(request("/api/dashboard"), new MockHttpServletResponse(), countingChain(calls));

            assertThat(calls).hasValue(1);
        }
    }

    private static void authenticate(Long id, String name) {
        authenticate(id, name, AppUser.Role.USER);
    }

    private static void authenticate(Long id, String name, AppUser.Role role) {
        AppUser user = new AppUser(name, "hash", role);
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
