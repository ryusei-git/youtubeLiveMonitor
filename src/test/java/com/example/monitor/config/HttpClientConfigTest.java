package com.example.monitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HttpClientConfig")
class HttpClientConfigTest {

    @Nested
    @DisplayName("httpClient()")
    class HttpClientBean {

        @Test
        @DisplayName("正常系：接続タイムアウト10秒・リダイレクト追従ありのHttpClientを生成する")
        void testMethod01() {
            HttpClientConfig config = new HttpClientConfig();

            HttpClient httpClient = config.httpClient();

            assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(10));
            assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NORMAL);
        }
    }
}
