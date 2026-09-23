package com.example.monitor.config;

import com.example.monitor.config.MonitorProperties.AdminProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MonitorProperties")
class MonitorPropertiesTest {

    @Nested
    @DisplayName("コンストラクタ")
    class Constructor {

        @Test
        @DisplayName("正常系：YouTube・Twitch・Discord・録画・初期管理者の各設定をそのまま保持する")
        void testMethod01() {
            YouTubeProperties youtube = new YouTubeProperties("api-key", 60);
            TwitchProperties twitch = new TwitchProperties("twitch-id", "twitch-secret");
            DiscordProperties discord = new DiscordProperties("https://discord.com/api/webhooks/x/y");
            RecordingProperties recording = new RecordingProperties("recordings", 720);
            AdminProperties admin = new AdminProperties("admin", "");

            MonitorProperties properties = new MonitorProperties(youtube, twitch, discord, recording, admin);

            assertThat(properties.youtube().apiKey()).isEqualTo("api-key");
            assertThat(properties.youtube().intervalSeconds()).isEqualTo(60);
            assertThat(properties.twitch().clientId()).isEqualTo("twitch-id");
            assertThat(properties.twitch().isConfigured()).isTrue();
            assertThat(properties.discord().webhookUrl()).isEqualTo("https://discord.com/api/webhooks/x/y");
            assertThat(properties.recording().directory()).isEqualTo("recordings");
            assertThat(properties.recording().maxHeight()).isEqualTo(720);
            assertThat(properties.admin().username()).isEqualTo("admin");
            assertThat(properties.admin().password()).isEmpty();
        }

        @Test
        @DisplayName("正常系：初期管理者の設定を変えた場合もその値を保持する")
        void testMethod02() {
            YouTubeProperties youtube = new YouTubeProperties("api-key", 60);
            TwitchProperties twitch = new TwitchProperties("twitch-id", "twitch-secret");
            DiscordProperties discord = new DiscordProperties("https://discord.com/api/webhooks/x/y");
            RecordingProperties recording = new RecordingProperties("recordings", 720);
            AdminProperties admin = new AdminProperties("owner", "s3cret");

            MonitorProperties properties = new MonitorProperties(youtube, twitch, discord, recording, admin);

            assertThat(properties.admin().username()).isEqualTo("owner");
            assertThat(properties.admin().password()).isEqualTo("s3cret");
        }
    }
}
