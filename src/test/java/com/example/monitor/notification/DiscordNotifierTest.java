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

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiscordNotifier")
@SuppressWarnings("unchecked")
class DiscordNotifierTest {

    /** {@code DiscordWebhookUrl.isValid} を通る形の Webhook の URL。 */
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/123456789012345678/dummy-token";

    /** 利用者ごとの Webhook。全体向けの WEBHOOK_URL と取り違えていないかを見分けるため、別の値にする。 */
    private static final String USER_WEBHOOK_URL = "https://discord.com/api/webhooks/987654321098765432/user-token";

    /** Discord の Webhook の形でない URL。トークンに当たる部分が例外の文言へ漏れないかを見る。 */
    private static final String MALFORMED_URL = "https://example.com/api/webhooks/1/secret-token";

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

    /**
     * Discord の応答のモックを作る。
     *
     * <p>2xx のときは本文を読まないので、{@code body()} をスタブしない（スタブすると strict stubs で落ちる）。
     *
     * @param statusCode 状態コード
     * @param body       応答本文。2xx のときは使わない
     * @return 応答のモック
     */
    private HttpResponse<String> response(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        if (statusCode / 100 != 2) {
            when(response.body()).thenReturn(body);
        }
        return response;
    }

    @Nested
    @DisplayName("warnIfWebhookUrlUnusable()")
    class WarnIfWebhookUrlUnusable {

        @Test
        @DisplayName("正常系：全体向けのWebhook URLの形が崩れていても例外を投げない（起動を止めない）")
        void testMethod01() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(MALFORMED_URL), httpClient);

            assertThatCode(notifier::warnIfWebhookUrlUnusable).doesNotThrowAnyException();

            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("正常系：全体向けのWebhook URLが未設定でも例外を投げない")
        void testMethod02() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(null), httpClient);

            assertThatCode(notifier::warnIfWebhookUrlUnusable).doesNotThrowAnyException();

            verifyNoInteractions(httpClient);
        }
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

        @Test
        @DisplayName("正常系：利用者のWebhookを指定したら、全体向けではなくそのURLへPOSTする")
        void testMethod06() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(WEBHOOK_URL), httpClient);
            HttpResponse<String> response = response(200, null);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .watchUrl("https://www.youtube.com/watch?v=video001")
                    .build();

            notifier.sendLiveStartNotification(USER_WEBHOOK_URL, liveStream);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertThat(captor.getValue().uri().toString()).isEqualTo(USER_WEBHOOK_URL + "?wait=true");
            JsonNode embed = JsonMapper.shared().readTree(requestBody(captor.getValue())).path("embeds").path(0);
            assertThat(embed.path("title").asString()).isEqualTo("配信タイトル");
            assertThat(embed.path("description").asString()).isEqualTo("🔴 配信が開始されました");
            assertThat(embed.path("color").asInt()).isEqualTo(16711680);
        }

        @Test
        @DisplayName("異常系：全体向けのWebhook URLの形が崩れていたら送らず、例外の文言にURLを含めない")
        void testMethod07() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(MALFORMED_URL), httpClient);
            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .watchUrl("https://www.youtube.com/watch?v=video001")
                    .build();

            assertThatThrownBy(() -> notifier.sendLiveStartNotification(liveStream))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("secret-token")
                    .hasMessageNotContaining("example.com");

            verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    @Nested
    @DisplayName("sendTestNotification()")
    class SendTestNotification {

        @Test
        @DisplayName("正常系：?wait=trueを付けたURLへ、JSONの本文と10秒の上限を付けてPOSTする")
        void testMethod01() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);
            HttpResponse<String> response = response(200, null);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            notifier.sendTestNotification(WEBHOOK_URL);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            HttpRequest request = captor.getValue();
            assertThat(request.uri().toString()).isEqualTo(WEBHOOK_URL + "?wait=true");
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.headers().firstValue("Content-Type")).isEqualTo(Optional.of("application/json"));
            assertThat(request.timeout()).isEqualTo(Optional.of(Duration.ofSeconds(10)));
            JsonNode embed = JsonMapper.shared().readTree(requestBody(request)).path("embeds").path(0);
            assertThat(embed.path("title").asString()).isEqualTo("テスト通知");
            assertThat(embed.path("description").asString()).isEqualTo(
                    "YouTube Live Monitor からのテスト通知です。購読しているチャンネルの配信が始まると、ここに通知が届きます。");
            assertThat(embed.path("color").asInt()).isEqualTo(16711680);
        }

        @Test
        @DisplayName("正常系：2xxなら204でも例外を投げない")
        void testMethod02() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);
            HttpResponse<String> response = response(204, null);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            assertThatCode(() -> notifier.sendTestNotification(WEBHOOK_URL)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系：DiscordのWebhookの形でないURLへは送らず、例外の文言にURLを含めない")
        void testMethod03() throws Exception {
            // 失敗の文言は利用者向け API の応答と UserNotification.lastError に残るので、URL（トークン）を入れない（DiscordNotifier.post の JavaDoc）
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);

            assertThatThrownBy(() -> notifier.sendTestNotification(MALFORMED_URL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Discord の Webhook の URL ではないため送信しません")
                    .hasMessageNotContaining("secret-token");

            verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }

        @Test
        @DisplayName("異常系：2xx以外なら状態コードと応答本文を文言に入れる")
        void testMethod04() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);
            HttpResponse<String> response = response(404, "{\"message\": \"Unknown Webhook\", \"code\": 10015}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            assertThatThrownBy(() -> notifier.sendTestNotification(WEBHOOK_URL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Discord が HTTP 404 を返しました: {\"message\": \"Unknown Webhook\", \"code\": 10015}");
        }

        @Test
        @DisplayName("異常系：応答本文は200文字で切る")
        void testMethod05() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);
            HttpResponse<String> response = response(500, "x".repeat(300));
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            assertThatThrownBy(() -> notifier.sendTestNotification(WEBHOOK_URL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Discord が HTTP 500 を返しました: " + "x".repeat(200));
        }

        @Test
        @DisplayName("異常系：応答本文が無くても失敗にする")
        void testMethod06() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);
            HttpResponse<String> response = response(502, null);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            assertThatThrownBy(() -> notifier.sendTestNotification(WEBHOOK_URL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Discord が HTTP 502 を返しました: ");
        }

        @Test
        @DisplayName("異常系：通信の例外は、URLを含めずにIllegalStateExceptionにする")
        void testMethod07() throws Exception {
            // 失敗の文言は利用者向け API の応答と UserNotification.lastError に残るので、URL（トークン）を入れない（DiscordNotifier.post の JavaDoc）
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection reset"));

            assertThatThrownBy(() -> notifier.sendTestNotification(WEBHOOK_URL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Discord へ送信できませんでした: ")
                    .hasMessageContaining("Connection reset")
                    .hasMessageNotContaining("dummy-token");
        }

        @Test
        @DisplayName("異常系：送信中に割り込まれたらIllegalStateExceptionにし、割り込みの印を残す")
        void testMethod08() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new InterruptedException());

            Throwable thrown = catchThrowable(() -> notifier.sendTestNotification(WEBHOOK_URL));
            // 割り込みの印を読み、同時に消す（同じスレッドで動く後のテストに残さない）
            boolean interrupted = Thread.interrupted();

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("Discord への送信が中断されました");
            assertThat(interrupted).isTrue();
        }
    }

    @Nested
    @DisplayName("sendAdminAlert()")
    class SendAdminAlert {

        @Test
        @DisplayName("正常系：全体向けのWebhookへ、本文をそのまま説明文にしてPOSTする")
        void testMethod01() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(WEBHOOK_URL), httpClient);
            HttpResponse<String> response = response(200, null);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            notifier.sendAdminAlert("録画の保存先の空き容量が 1GB を切りました");

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertThat(captor.getValue().uri().toString()).isEqualTo(WEBHOOK_URL + "?wait=true");
            JsonNode embed = JsonMapper.shared().readTree(requestBody(captor.getValue())).path("embeds").path(0);
            assertThat(embed.path("title").asString()).isEqualTo("管理者への通知");
            assertThat(embed.path("description").asString()).isEqualTo("録画の保存先の空き容量が 1GB を切りました");
            assertThat(embed.path("color").asInt()).isEqualTo(16711680);
        }

        @Test
        @DisplayName("異常系：全体向けのWebhook URLが未設定なら送らずに例外を投げる")
        void testMethod02() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""), httpClient);

            assertThatThrownBy(() -> notifier.sendAdminAlert("録画の保存先の空き容量が 1GB を切りました"))
                    .isInstanceOf(IllegalStateException.class);

            verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }

        @Test
        @DisplayName("異常系：全体向けのWebhook URLの形が崩れていたら送らず、例外の文言にURLを含めない")
        void testMethod03() throws Exception {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(MALFORMED_URL), httpClient);

            assertThatThrownBy(() -> notifier.sendAdminAlert("録画の保存先の空き容量が 1GB を切りました"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("secret-token");

            verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }
}
