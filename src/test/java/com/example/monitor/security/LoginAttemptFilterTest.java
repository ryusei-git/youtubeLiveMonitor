package com.example.monitor.security;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AppUser.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoginAttemptFilter")
class LoginAttemptFilterTest {
    private final MutableClock clock = new MutableClock();
    private final LoginAttemptFilter filter = new LoginAttemptFilter(new LoginAttemptLimiter(clock));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("doFilterInternal()")
    class DoFilterInternal {
        @Test
        @DisplayName("異常系：存在する利用者と存在しない利用者の制限応答は同一になる")
        void testMethod01() throws Exception {
            for (int index = 0; index < 5; index++) {
                perform("known", "198.51.100.1", null, failureChain());
                perform("missing", "198.51.100.2", null, failureChain());
            }
            AtomicInteger downstreamCalls = new AtomicInteger();
            FilterChain countingChain = (request, response) -> downstreamCalls.incrementAndGet();

            MockHttpServletResponse known = perform("known", "198.51.100.1", null, countingChain);
            MockHttpServletResponse missing = perform("missing", "198.51.100.2", null, countingChain);

            assertThat(known.getStatus()).isEqualTo(429);
            assertThat(missing.getStatus()).isEqualTo(known.getStatus());
            assertThat(missing.getHeader("Retry-After")).isEqualTo(known.getHeader("Retry-After")).isEqualTo("900");
            assertThat(missing.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(missing.getContentAsString()).isEqualTo(known.getContentAsString());
            assertThat(downstreamCalls).hasValue(0);
        }

        @Test
        @DisplayName("異常系：X-Forwarded-Forを変えても接続元IPの制限は迂回できない")
        void testMethod02() throws Exception {
            for (int index = 0; index < 20; index++) {
                perform("user-" + index, "198.51.100.1", "203.0.113." + index, failureChain());
            }

            MockHttpServletResponse blocked = perform("new-user", "198.51.100.1", "203.0.113.250",
                    (request, response) -> { throw new AssertionError("制限中に認証処理へ進んだ"); });
            assertThat(blocked.getStatus()).isEqualTo(429);
            assertThat(blocked.getHeader("Retry-After")).isEqualTo("900");
        }

        @Test
        @DisplayName("正常系：正しい利用者の認証成功は失敗回数をリセットする")
        void testMethod03() throws Exception {
            for (int index = 0; index < 4; index++) perform("known", "198.51.100.1", null, failureChain());
            AppUser user = new AppUser("known", "hash", Role.USER);
            AuthenticatedAppUser principal = new AuthenticatedAppUser(user);
            FilterChain successChain = (request, response) -> SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

            perform(" known ", "198.51.100.1", null, successChain);
            for (int index = 0; index < 4; index++) perform("known", "198.51.100.1", null, failureChain());
            MockHttpServletResponse allowed = perform("known", "198.51.100.1", null, failureChain());

            assertThat(allowed.getStatus()).isEqualTo(302);
        }

        @Test
        @DisplayName("異常系：認証処理の例外でも枠を解放し15分後に再試行できる")
        void testMethod04() throws Exception {
            for (int index = 0; index < 4; index++) perform("known", "198.51.100.1", null, failureChain());
            assertThatThrownBy(() -> perform("known", "198.51.100.1", null,
                    (request, response) -> { throw new ServletException("authentication failed unexpectedly"); }))
                    .isInstanceOf(ServletException.class);

            clock.advance(Duration.ofMinutes(15));
            MockHttpServletResponse allowed = perform("known", "198.51.100.1", null, failureChain());
            assertThat(allowed.getStatus()).isEqualTo(302);
        }
    }

    @Nested
    @DisplayName("shouldNotFilter()")
    class ShouldNotFilter {
        @Test
        @DisplayName("正常系：POSTのログイン経路だけを制限対象にする")
        void testMethod01() {
            assertThat(filter.shouldNotFilter(request("POST", "/api/auth/login", "user", "198.51.100.1", null)))
                    .isFalse();
            assertThat(filter.shouldNotFilter(request("GET", "/api/auth/login", "user", "198.51.100.1", null)))
                    .isTrue();
            assertThat(filter.shouldNotFilter(request("POST", "/api/auth/logout", "user", "198.51.100.1", null)))
                    .isTrue();
        }
    }

    private MockHttpServletResponse perform(String username, String address, String forwardedFor, FilterChain chain)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
        try {
            filter.doFilter(request("POST", "/api/auth/login", username, address, forwardedFor), response, chain);
        } finally {
            SecurityContextHolder.clearContext();
        }
        return response;
    }

    private static MockHttpServletRequest request(String method, String path, String username,
                                                   String address, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr(address);
        request.setParameter("username", username);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }

    private static FilterChain failureChain() {
        return (request, response) -> ((MockHttpServletResponse) response).setStatus(302);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-24T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }
}
