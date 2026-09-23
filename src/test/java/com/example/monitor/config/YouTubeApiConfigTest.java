package com.example.monitor.config;

import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.google.api.services.youtube.YouTube;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("YouTubeApiConfig")
class YouTubeApiConfigTest {

    @Nested
    @DisplayName("youtube()")
    class YoutubeBean {

        @Test
        @DisplayName("正常系：APIキーが設定されている場合でも例外なくクライアントを生成できる")
        void testMethod01() {
            MonitorProperties properties = new MonitorProperties(
                    new YouTubeProperties("dummy-api-key", 120), new TwitchProperties("", ""), new DiscordProperties(""),
                    new RecordingProperties("recordings", 1080), new MonitorProperties.AdminProperties("admin", ""));
            YouTubeApiConfig config = new YouTubeApiConfig(properties);

            YouTube youtube = config.youtube();

            assertThat(youtube).isNotNull();
            assertThat(youtube.getApplicationName()).isEqualTo("youtube-live-monitor");
        }

        @Test
        @DisplayName("正常系：APIキーが未設定でも例外を発生させずにクライアントを生成できる")
        void testMethod02() {
            MonitorProperties properties = new MonitorProperties(
                    new YouTubeProperties("", 120), new TwitchProperties("", ""), new DiscordProperties(""),
                    new RecordingProperties("recordings", 1080), new MonitorProperties.AdminProperties("admin", ""));
            YouTubeApiConfig config = new YouTubeApiConfig(properties);

            YouTube youtube = config.youtube();

            assertThat(youtube).isNotNull();
        }
    }
}
