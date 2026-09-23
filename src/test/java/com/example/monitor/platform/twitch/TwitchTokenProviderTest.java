package com.example.monitor.platform.twitch;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TwitchTokenProvider")
@SuppressWarnings("unchecked")
class TwitchTokenProviderTest {

    @Mock
    private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TwitchTokenProvider newProvider(String clientId, String clientSecret) {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties(clientId, clientSecret),
                new DiscordProperties(""),
                new RecordingProperties("recordings", 0),
                new MonitorProperties.AdminProperties("admin", ""));
        return new TwitchTokenProvider(httpClient, objectMapper, properties);
    }

    /**
     * 指定した状態コードと本文を返す応答を作る。
     *
     * @param statusCode HTTP の状態コード
     * @param body       応答本文
     * @return 組み立てたモック応答
     */
    private HttpResponse<String> mockResponse(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        if (statusCode == 200) {
            when(response.body()).thenReturn(body);
        }
        return response;
    }

    @Nested
    @DisplayName("accessToken()")
    class AccessToken {

        @Test
        @DisplayName("正常系：取得したトークンを返す")
        void testMethod01() throws Exception {
            HttpResponse<String> response = mockResponse(200,
                    "{\"access_token\":\"token-abc\",\"expires_in\":5000000,\"token_type\":\"bearer\"}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            assertThat(newProvider("id", "secret").accessToken()).isEqualTo("token-abc");
        }

        @Test
        @DisplayName("正常系：有効期限内なら2回目は取得し直さない")
        void testMethod02() throws Exception {
            HttpResponse<String> response = mockResponse(200,
                    "{\"access_token\":\"token-abc\",\"expires_in\":5000000}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            TwitchTokenProvider provider = newProvider("id", "secret");

            provider.accessToken();
            provider.accessToken();

            // 通信は1回だけ（キャッシュが効いている）
            verify(httpClient, times(1)).send(any(HttpRequest.class), any());
        }

        @Test
        @DisplayName("正常系：有効期限が目前なら取得し直す")
        void testMethod03() throws Exception {
            // 期限に余裕（EXPIRY_MARGIN）が無い場合は使い回さず取り直す
            HttpResponse<String> response = mockResponse(200,
                    "{\"access_token\":\"token-abc\",\"expires_in\":10}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            TwitchTokenProvider provider = newProvider("id", "secret");

            provider.accessToken();
            provider.accessToken();

            verify(httpClient, times(2)).send(any(HttpRequest.class), any());
        }

        @Test
        @DisplayName("異常系：Client ID / Secret が未設定なら通信せず例外を投げる")
        void testMethod04() throws Exception {
            assertThatThrownBy(() -> newProvider("", "").accessToken())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未設定");

            verify(httpClient, times(0)).send(any(HttpRequest.class), any());
        }

        @Test
        @DisplayName("異常系：認証に失敗した場合は例外を投げる")
        void testMethod05() throws Exception {
            HttpResponse<String> response = mockResponse(403, "");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            assertThatThrownBy(() -> newProvider("id", "bad-secret").accessToken())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("403");
        }

        @Test
        @DisplayName("異常系：通信に失敗した場合は例外を投げる")
        void testMethod06() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("接続できません"));

            assertThatThrownBy(() -> newProvider("id", "secret").accessToken())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("異常系：応答にトークンが含まれない場合は例外を投げる")
        void testMethod07() throws Exception {
            HttpResponse<String> response = mockResponse(200, "{\"expires_in\":5000000}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(response);

            assertThatThrownBy(() -> newProvider("id", "secret").accessToken())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("invalidate()")
    class Invalidate {

        @Test
        @DisplayName("正常系：破棄すると次回に取得し直す")
        void testMethod01() throws Exception {
            // 期限内でも Twitch 側で失効させられることがあるため、捨てて取り直せる必要がある
            HttpResponse<String> response = mockResponse(200,
                    "{\"access_token\":\"token-abc\",\"expires_in\":5000000}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            TwitchTokenProvider provider = newProvider("id", "secret");

            provider.accessToken();
            provider.invalidate();
            provider.accessToken();

            verify(httpClient, times(2)).send(any(HttpRequest.class), any());
        }
    }
}
