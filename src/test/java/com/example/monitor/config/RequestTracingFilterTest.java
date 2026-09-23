package com.example.monitor.config;

import com.example.monitor.util.RequestContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequestTracingFilter")
class RequestTracingFilterTest {

    private final RequestTracingFilter filter = new RequestTracingFilter();

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    /**
     * 後続の処理が走っている最中の相関 ID を記録するフィルターチェーンを作る。
     *
     * @param observed 観測した相関 ID の記録先
     * @return 組み立てたフィルターチェーン
     */
    private MockFilterChain recordingChain(List<String> observed) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                                 jakarta.servlet.ServletResponse response) {
                observed.add(RequestContext.currentRequestId());
            }
        };
    }

    @Nested
    @DisplayName("doFilterInternal()")
    class DoFilterInternal {

        @Test
        @DisplayName("正常系：後続の処理中は相関IDが載っている")
        void testMethod01() throws ServletException, IOException {
            List<String> observed = new ArrayList<>();

            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    recordingChain(observed));

            assertThat(observed).hasSize(1);
            assertThat(observed.get(0)).isNotBlank();
        }

        @Test
        @DisplayName("正常系：応答ヘッダーに相関IDを載せる")
        void testMethod02() throws ServletException, IOException {
            List<String> observed = new ArrayList<>();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(new MockHttpServletRequest(), response, recordingChain(observed));

            // ログに出たIDと利用者に示すIDが一致していないと突き合わせられない
            assertThat(response.getHeader("X-Request-Id")).isEqualTo(observed.get(0));
        }

        @Test
        @DisplayName("正常系：リクエストごとに異なる相関IDを発行する")
        void testMethod03() throws ServletException, IOException {
            List<String> observed = new ArrayList<>();

            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    recordingChain(observed));
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    recordingChain(observed));

            assertThat(observed.get(0)).isNotEqualTo(observed.get(1));
        }

        @Test
        @DisplayName("正常系：処理が終わると相関IDを残さない")
        void testMethod04() throws ServletException, IOException {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    recordingChain(new ArrayList<>()));

            assertThat(RequestContext.currentRequestId()).isNull();
        }

        @Test
        @DisplayName("異常系：後続の処理が例外を投げても相関IDを残さない")
        void testMethod05() {
            // 残すと、スレッドを使い回す次のリクエストのログに前のIDが混ざり、
            // 別人の操作を同一の操作として追ってしまう
            MockFilterChain failingChain = new MockFilterChain() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest request,
                                     jakarta.servlet.ServletResponse response) {
                    throw new IllegalStateException("後続の処理が失敗した");
                }
            };

            assertThatThrownBy(() -> filter.doFilter(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), failingChain))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(RequestContext.currentRequestId()).isNull();
        }
    }
}
