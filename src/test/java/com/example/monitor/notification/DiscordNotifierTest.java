package com.example.monitor.notification;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookEmbed;
import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.dto.LiveStreamDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiscordNotifier")
class DiscordNotifierTest {

    @Mock
    private WebhookClient webhookClient;

    private MonitorProperties monitorProperties(String webhookUrl) {
        return new MonitorProperties(new YouTubeProperties("", 120), new TwitchProperties("", ""), new DiscordProperties(webhookUrl),
                new RecordingProperties("recordings", 1080), new MonitorProperties.AdminProperties("admin", ""));
    }

    @Nested
    @DisplayName("initializeWebhookClient()")
    class InitializeWebhookClient {

        @Test
        @DisplayName("正常系：Webhook URLが設定されている場合はWebhookClientが生成される")
        void testMethod01() {
            DiscordNotifier notifier = new DiscordNotifier(
                    monitorProperties("https://discord.com/api/webhooks/123456789012345678/dummy-token"));

            notifier.initializeWebhookClient();

            Object client = ReflectionTestUtils.getField(notifier, "webhookClient");
            assertThat(client).isNotNull();
        }

        @Test
        @DisplayName("正常系：Webhook URLが空文字の場合はWebhookClientがnullのままになる")
        void testMethod02() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""));

            notifier.initializeWebhookClient();

            Object client = ReflectionTestUtils.getField(notifier, "webhookClient");
            assertThat(client).isNull();
        }

        @Test
        @DisplayName("正常系：Webhook URLがnullの場合もWebhookClientがnullのままになる")
        void testMethod03() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(null));

            notifier.initializeWebhookClient();

            Object client = ReflectionTestUtils.getField(notifier, "webhookClient");
            assertThat(client).isNull();
        }
    }

    @Nested
    @DisplayName("sendLiveStartNotification()")
    class SendLiveStartNotification {

        @Test
        @DisplayName("正常系：WebhookClientへタイトル・チャンネル名を含むembedを送信する")
        void testMethod01() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties("https://discord.com/api/webhooks/x/y"));
            ReflectionTestUtils.setField(notifier, "webhookClient", webhookClient);
            when(webhookClient.send(any(WebhookEmbed.class))).thenReturn(CompletableFuture.completedFuture(null));

            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .watchUrl("https://www.youtube.com/watch?v=video001")
                    .build();

            notifier.sendLiveStartNotification(liveStream);

            ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
            verify(webhookClient, times(1)).send(captor.capture());
            WebhookEmbed sentEmbed = captor.getValue();
            assertThat(sentEmbed.getTitle().getText()).isEqualTo("配信タイトル");
            assertThat(sentEmbed.getTitle().getUrl()).isEqualTo("https://www.youtube.com/watch?v=video001");
            assertThat(sentEmbed.getAuthor().getName()).isEqualTo("テストチャンネル");
        }

        @Test
        @DisplayName("正常系：YouTube以外の視聴URLもそのままリンク先になる")
        void testMethod05() {
            // 以前は LiveStreamDetails 側で videoId から YouTube の URL を組み立てていたため、
            // Twitch の配信を通知すると存在しない YouTube の URL が貼られていた。
            // 渡された URL がそのまま使われることを固定する
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties("https://discord.com/api/webhooks/x/y"));
            ReflectionTestUtils.setField(notifier, "webhookClient", webhookClient);
            when(webhookClient.send(any(WebhookEmbed.class))).thenReturn(CompletableFuture.completedFuture(null));

            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("320300019550")
                    .title("Twitchの配信")
                    .channelTitle("テストチャンネル")
                    .watchUrl("https://www.twitch.tv/testuser")
                    .build();

            notifier.sendLiveStartNotification(liveStream);

            ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
            verify(webhookClient, times(1)).send(captor.capture());
            assertThat(captor.getValue().getTitle().getUrl()).isEqualTo("https://www.twitch.tv/testuser");
        }

        @Test
        @DisplayName("正常系：サムネイルURLが設定されている場合はembedの画像として設定される")
        void testMethod02() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties("https://discord.com/api/webhooks/x/y"));
            ReflectionTestUtils.setField(notifier, "webhookClient", webhookClient);
            when(webhookClient.send(any(WebhookEmbed.class))).thenReturn(CompletableFuture.completedFuture(null));

            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .thumbnailUrl("https://example.com/thumb.jpg")
                    .build();

            notifier.sendLiveStartNotification(liveStream);

            ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
            verify(webhookClient).send(captor.capture());
            assertThat(captor.getValue().getImageUrl()).isEqualTo("https://example.com/thumb.jpg");
        }

        @Test
        @DisplayName("正常系：サムネイルURLが無い場合はembedの画像が設定されない")
        void testMethod03() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties("https://discord.com/api/webhooks/x/y"));
            ReflectionTestUtils.setField(notifier, "webhookClient", webhookClient);
            when(webhookClient.send(any(WebhookEmbed.class))).thenReturn(CompletableFuture.completedFuture(null));

            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .build();

            notifier.sendLiveStartNotification(liveStream);

            ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
            verify(webhookClient).send(captor.capture());
            assertThat(captor.getValue().getImageUrl()).isNull();
        }

        @Test
        @DisplayName("異常系：Webhook URLが未設定の場合はIllegalStateExceptionが発生する")
        void testMethod04() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""));
            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .build();

            assertThatThrownBy(() -> notifier.sendLiveStartNotification(liveStream))
                    .isInstanceOf(IllegalStateException.class);

            verify(webhookClient, never()).send(any(WebhookEmbed.class));
        }
    }

    @Nested
    @DisplayName("closeWebhookClient()")
    class CloseWebhookClient {

        @Test
        @DisplayName("正常系：WebhookClientが設定されている場合はcloseが呼ばれる")
        void testMethod01() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties("https://discord.com/api/webhooks/x/y"));
            ReflectionTestUtils.setField(notifier, "webhookClient", webhookClient);

            notifier.closeWebhookClient();

            verify(webhookClient, times(1)).close();
        }

        @Test
        @DisplayName("正常系：WebhookClientが未設定の場合は例外を発生させずに何もしない")
        void testMethod02() {
            DiscordNotifier notifier = new DiscordNotifier(monitorProperties(""));

            assertThatCode(notifier::closeWebhookClient).doesNotThrowAnyException();
        }
    }
}
