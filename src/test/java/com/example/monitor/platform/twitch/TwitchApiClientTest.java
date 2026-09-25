package com.example.monitor.platform.twitch;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TwitchApiClient")
@SuppressWarnings("unchecked")
class TwitchApiClientTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private TwitchTokenProvider twitchTokenProvider;

    private TwitchApiClient apiClient;

    @BeforeEach
    void setUp() {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties("", 120),
                new TwitchProperties("client-id", "client-secret"),
                new DiscordProperties(""),
                new RecordingProperties("recordings", 0),
                new MonitorProperties.AdminProperties("admin", ""));
        // 到達しないテスト（空リストなど）もあるため lenient にする
        lenient().when(twitchTokenProvider.accessToken()).thenReturn("token-abc");
        apiClient = new TwitchApiClient(httpClient, new ObjectMapper(), properties, twitchTokenProvider);
    }

    /**
     * 指定した状態コードと本文を返す応答を作る。
     *
     * <p>モックを組み立てるヘルパーは {@code when(...)} の引数の中で呼ばないこと
     * （入れ子のスタブになり UnfinishedStubbingException になる）。必ずローカル変数へ受ける。
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
            // 残りリクエスト数の警告はヘッダーを見るため、空のヘッダーを持たせる
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        }
        return response;
    }

    /**
     * 配信 1 件分の JSON を作る。
     *
     * @param streamId 配信 ID
     * @param login    配信者のログイン名
     * @return 応答本文の JSON
     */
    private String streamJson(String streamId, String login) {
        return """
                {"data":[{"id":"%s","user_id":"111","user_login":"%s","user_name":"Name",
                "title":"配信タイトル","game_name":"Just Chatting","viewer_count":10,
                "thumbnail_url":"https://example.com/p-{width}x{height}.jpg",
                "started_at":"2026-09-19T22:04:37Z"}]}
                """.formatted(streamId, login);
    }

    @Nested
    @DisplayName("fetchLiveStreams()")
    class FetchLiveStreams {

        @Test
        @DisplayName("正常系：配信中のチャンネルを返す")
        void testMethod01() throws Exception {
            HttpResponse<String> response = mockResponse(200, streamJson("555", "alpha"));
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            List<TwitchStream> streams = apiClient.fetchLiveStreams(List.of("111"));

            assertThat(streams).hasSize(1);
            assertThat(streams.get(0).id()).isEqualTo("555");
            assertThat(streams.get(0).userLogin()).isEqualTo("alpha");
            assertThat(streams.get(0).startedAt()).isEqualTo("2026-09-19T22:04:37Z");
        }

        @Test
        @DisplayName("正常系：応答が空なら空リストを返す（誰も配信していない）")
        void testMethod02() throws Exception {
            HttpResponse<String> response = mockResponse(200, "{\"data\":[]}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            assertThat(apiClient.fetchLiveStreams(List.of("111"))).isEmpty();
        }

        @Test
        @DisplayName("正常系：空のリストなら通信しない")
        void testMethod03() {
            assertThat(apiClient.fetchLiveStreams(List.of())).isEmpty();

            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("正常系：100件を超える場合は分割して問い合わせる")
        void testMethod04() throws Exception {
            HttpResponse<String> response = mockResponse(200, "{\"data\":[]}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            List<String> ids = IntStream.range(0, 150).mapToObj(String::valueOf).toList();
            apiClient.fetchLiveStreams(ids);

            // 100件上限なので 150件は 2 回に分かれる
            verify(httpClient, times(2)).send(any(HttpRequest.class), any());
        }

        @Test
        @DisplayName("異常系：エラー応答なら例外を投げる（「配信していない」と混同させない）")
        void testMethod05() throws Exception {
            HttpResponse<String> response = mockResponse(500, "");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            assertThatThrownBy(() -> apiClient.fetchLiveStreams(List.of("111")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("500");
        }

        @Test
        @DisplayName("異常系：通信に失敗した場合は例外を投げる")
        void testMethod06() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("接続できません"));

            assertThatThrownBy(() -> apiClient.fetchLiveStreams(List.of("111")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("正常系：401ならトークンを捨てて1度だけ再試行する")
        void testMethod07() throws Exception {
            HttpResponse<String> unauthorized = mockResponse(401, "");
            HttpResponse<String> success = mockResponse(200, "{\"data\":[]}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(unauthorized, success);

            apiClient.fetchLiveStreams(List.of("111"));

            verify(twitchTokenProvider).invalidate();
            verify(httpClient, times(2)).send(any(HttpRequest.class), any());
        }
    }

    @Nested
    @DisplayName("findUserByLogin()")
    class FindUserByLogin {

        @Test
        @DisplayName("正常系：ログイン名からユーザー情報を返す")
        void testMethod01() throws Exception {
            HttpResponse<String> response = mockResponse(200,
                    "{\"data\":[{\"id\":\"12826\",\"login\":\"alpha\",\"display_name\":\"Alpha\"}]}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            Optional<TwitchUser> user = apiClient.findUserByLogin("alpha");

            assertThat(user).isPresent();
            assertThat(user.orElseThrow().id()).isEqualTo("12826");
            assertThat(user.orElseThrow().displayName()).isEqualTo("Alpha");
        }

        @Test
        @DisplayName("正常系：存在しないログイン名なら空を返す")
        void testMethod02() throws Exception {
            HttpResponse<String> response = mockResponse(200, "{\"data\":[]}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            assertThat(apiClient.findUserByLogin("notfound")).isEmpty();
        }
    }
    @Nested
    class FetchRecentVideos {
        @Test
        @DisplayName("正常系：所有者を照合し配信IDとVODのURLを別々に扱う")
        void testMethod01() throws Exception {
            var response = mockResponse(200, """
                {"data":[{"id":"999","stream_id":"123","user_id":"42","title":"配信",
                "published_at":"2026-09-23T01:00:00Z","thumbnail_url":"https://static-cdn.jtvnw.net/a-%{width}x%{height}.jpg"},
                {"id":"888","stream_id":"","user_id":"other","title":"別人"}]}
                """);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            var videos = apiClient.fetchRecentVideos("42");
            assertThat(videos).hasSize(1);
            assertThat(videos.getFirst().key()).isEqualTo("TWITCH_stream_123");
            assertThat(videos.getFirst().watchUrl()).isEqualTo("https://www.twitch.tv/videos/999");
            assertThat(videos.getFirst().thumbnailUrl()).endsWith("640x360.jpg");
        }
    }

}
