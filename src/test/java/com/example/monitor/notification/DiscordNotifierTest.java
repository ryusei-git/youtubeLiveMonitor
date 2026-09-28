package com.example.monitor.notification;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.dto.LiveStreamDetails;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiscordNotifier")
@SuppressWarnings("unchecked")
class DiscordNotifierTest {

    /** {@code DiscordWebhookUrl.isValid} を通る形の Webhook の URL。 */
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/123456789012345678/dummy-token";

    /** Discord の Webhook への送信に使う（全体向けも {@code post} で送る）。 */
    @Mock
    private HttpClient httpClient;

    private MonitorProperties monitorProperties(String webhookUrl) {
        return new MonitorProperties(new YouTubeProperties("", 120), new TwitchProperties("", ""), new DiscordProperties(webhookUrl),
                new RecordingProperties("recordings", 1080), new MonitorProperties.AdminProperties("admin", ""));
    }

    /**
     * 送った要求の本文を文字列として読み出す。
     *
     * <p>{@link HttpRequest} は本文を {@code bodyPublisher()} としてしか持たないため、購読して集める。
     *
     * @param request 送った要求
     * @return 要求の本文
     */
    private static String requestBody(HttpRequest request) {
        HttpResponse.BodySubscriber<String> collector = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                collector.onSubscribe(subscription);
            }

            @Override
            public void onNext(ByteBuffer item) {
                collector.onNext(List.of(item));
            }

            @Override
            public void onError(Throwable throwable) {
                collector.onError(throwable);
            }

            @Override
            public void onComplete() {
                collector.onComplete();
            }
        });
        return collector.getBody().toCompletableFuture().join();
    }

    @Nested
    @DisplayName("sendLiveStartNotification()")
    class SendLiveStartNotification {

        @Test
        @DisplayName("正常系：全体向けのWebhookへタイトル・チャンネル名を含むembedをPOSTする")
        void testMethod01() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(WEBHOOK_URL), httpClient);
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .watchUrl("https://www.youtube.com/watch?v=video001")
                    .build();

            notifier.sendLiveStartNotification(liveStream);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertThat(captor.getValue().uri().toString()).isEqualTo(WEBHOOK_URL + "?wait=true");
            JsonNode embed = JsonMapper.shared().readTree(requestBody(captor.getValue())).path("embeds").path(0);
            assertThat(embed.path("title").asString()).isEqualTo("配信タイトル");
            assertThat(embed.path("url").asString()).isEqualTo("https://www.youtube.com/watch?v=video001");
            assertThat(embed.path("author").path("name").asString()).isEqualTo("テストチャンネル");
        }

        @Test
        @DisplayName("正常系：YouTube以外の視聴URLもそのままリンク先になる")
        void testMethod05() throws Exception {
            // 以前は LiveStreamDetails 側で videoId から YouTube の URL を組み立てていたため、
            // Twitch の配信を通知すると存在しない YouTube の URL が貼られていた。
            // 渡された URL がそのまま使われることを固定する
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(WEBHOOK_URL), httpClient);
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("320300019550")
                    .title("Twitchの配信")
                    .channelTitle("テストチャンネル")
                    .watchUrl("https://www.twitch.tv/testuser")
                    .build();

            notifier.sendLiveStartNotification(liveStream);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            JsonNode embed = JsonMapper.shared().readTree(requestBody(captor.getValue())).path("embeds").path(0);
            assertThat(embed.path("url").asString()).isEqualTo("https://www.twitch.tv/testuser");
        }

        @Test
        @DisplayName("正常系：サムネイルURLが設定されている場合はembedの画像として設定される")
        void testMethod02() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(WEBHOOK_URL), httpClient);
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .thumbnailUrl("https://example.com/thumb.jpg")
                    .build();

            notifier.sendLiveStartNotification(liveStream);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            JsonNode embed = JsonMapper.shared().readTree(requestBody(captor.getValue())).path("embeds").path(0);
            assertThat(embed.path("image").path("url").asString()).isEqualTo("https://example.com/thumb.jpg");
        }

        @Test
        @DisplayName("正常系：サムネイルURLが無い場合はembedの画像が設定されない")
        void testMethod03() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(WEBHOOK_URL), httpClient);
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .build();

            notifier.sendLiveStartNotification(liveStream);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            JsonNode embed = JsonMapper.shared().readTree(requestBody(captor.getValue())).path("embeds").path(0);
            assertThat(embed.has("image")).isFalse();
        }

        @Test
        @DisplayName("異常系：Webhook URLが未設定の場合はIllegalStateExceptionが発生する")
        void testMethod04() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);
            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .build();

            assertThatThrownBy(() -> notifier.sendLiveStartNotification(liveStream))
                    .isInstanceOf(IllegalStateException.class);

            verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }
}
