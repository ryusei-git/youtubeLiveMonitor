package com.example.monitor.service;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.NotificationOutcome;
import com.example.monitor.notification.DiscordNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDispatcher")
class NotificationDispatcherTest {

    @Mock
    private DiscordNotifier discordNotifier;

    @InjectMocks
    private NotificationDispatcher notificationDispatcher;

    @Nested
    @DisplayName("notifyLiveStreamStarted()")
    class NotifyLiveStreamStarted {

        @Test
        @DisplayName("正常系：送信に成功した場合は成功結果を返す")
        void testMethod01() {
            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .build();
            doNothing().when(discordNotifier).sendLiveStartNotification(any());

            NotificationOutcome outcome = notificationDispatcher.notifyLiveStreamStarted(liveStream);

            assertThat(outcome.successful()).isTrue();
            assertThat(outcome.errorMessage()).isNull();
        }

        @Test
        @DisplayName("異常系：送信で例外が発生した場合は例外を投げずに失敗結果を返す")
        void testMethod02() {
            LiveStreamDetails liveStream = LiveStreamDetails.builder()
                    .videoId("video001")
                    .title("配信タイトル")
                    .channelTitle("テストチャンネル")
                    .build();
            doThrow(new IllegalStateException("Webhook URLが未設定です"))
                    .when(discordNotifier).sendLiveStartNotification(any());

            NotificationOutcome outcome = notificationDispatcher.notifyLiveStreamStarted(liveStream);

            assertThat(outcome.successful()).isFalse();
            assertThat(outcome.errorMessage()).isEqualTo("Webhook URLが未設定です");
        }
    }
}
