package com.example.monitor.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestContext")
class RequestContextTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("callWithRequestId()")
    class CallWithRequestId {

        @Test
        @DisplayName("正常系：処理中は相関IDが載り、終わると消える")
        void testMethod01() {
            String observed = RequestContext.callWithRequestId("req-1", RequestContext::currentRequestId);

            assertThat(observed).isEqualTo("req-1");
            assertThat(RequestContext.currentRequestId()).isNull();
        }

        @Test
        @DisplayName("正常系：入れ子にしても外側の相関IDが消えない")
        void testMethod02() {
            // 単純に remove で終えると、内側が終わった時点で外側の印まで消える
            RequestContext.runWithRequestId("outer", () -> {
                RequestContext.runWithRequestId("inner", () -> { });
                assertThat(RequestContext.currentRequestId()).isEqualTo("outer");
            });

            assertThat(RequestContext.currentRequestId()).isNull();
        }

        @Test
        @DisplayName("異常系：処理が例外を投げても相関IDを残さない")
        void testMethod03() {
            // 残すと、スレッドを使い回す次のリクエストのログに前のIDが混ざる
            try {
                RequestContext.runWithRequestId("req-1", () -> {
                    throw new IllegalStateException("失敗");
                });
            } catch (IllegalStateException expected) {
                // 握りつぶしてよい。ここで見たいのは後片付けができているか
            }

            assertThat(RequestContext.currentRequestId()).isNull();
        }
    }

    @Nested
    @DisplayName("put() / restore()")
    class PutAndRestore {

        @Test
        @DisplayName("正常系：直前の値を返し、その値に戻せる")
        void testMethod01() {
            RequestContext.put("outer");

            String previous = RequestContext.put("inner");
            assertThat(previous).isEqualTo("outer");
            assertThat(RequestContext.currentRequestId()).isEqualTo("inner");

            RequestContext.restore(previous);
            assertThat(RequestContext.currentRequestId()).isEqualTo("outer");
        }

        @Test
        @DisplayName("正常系：直前の値が無ければ戻したときに消える")
        void testMethod02() {
            String previous = RequestContext.put("req-1");

            RequestContext.restore(previous);

            assertThat(RequestContext.currentRequestId()).isNull();
        }
    }

    @Nested
    @DisplayName("currentUsername()")
    class CurrentUsername {

        @Test
        @DisplayName("正常系：ログイン中は利用者名を返す")
        void testMethod01() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("admin", "n/a", AuthorityUtils.NO_AUTHORITIES));

            assertThat(RequestContext.currentUsername()).isEqualTo("admin");
        }

        @Test
        @DisplayName("正常系：未認証なら null を返す")
        void testMethod02() {
            assertThat(RequestContext.currentUsername()).isNull();
        }

        @Test
        @DisplayName("正常系：匿名の認証は利用者とみなさない")
        void testMethod03() {
            // "anonymousUser" という名前の人物と「誰か不明」を取り違えないようにする
            SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                    "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

            assertThat(RequestContext.currentUsername()).isNull();
        }
    }
}
