package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MonitoredChannelResponse")
class MonitoredChannelResponseTest {

    @Nested
    @DisplayName("from()")
    class From {

        @Test
        @DisplayName("正常系：エンティティの全項目がレスポンスに詰め替えられる")
        void testMethod01() {
            LocalDateTime checkedAt = LocalDateTime.of(2026, 9, 13, 10, 0, 0);
            LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 0, 0, 0);

            MonitoredChannel channel = new MonitoredChannel();
            channel.setId(1L);
            channel.setYoutubeChannelId("UCxxxxxxxx");
            channel.setChannelName("テストチャンネル");
            channel.setLastNotifiedVideoId("video001");
            channel.setCurrentlyLive(true);
            channel.setCurrentLiveVideoId("video002");
            channel.setLastCheckedAt(checkedAt);
            channel.setRecordEnabled(true);
            channel.setLastRecordedVideoId("video003");
            channel.setRecordTitleKeywords("【ASMR】,【生配信】");
            channel.setCreatedAt(createdAt);

            MonitoredChannelResponse response = MonitoredChannelResponse.from(channel);

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.youtubeChannelId()).isEqualTo("UCxxxxxxxx");
            assertThat(response.channelName()).isEqualTo("テストチャンネル");
            assertThat(response.lastNotifiedVideoId()).isEqualTo("video001");
            assertThat(response.currentlyLive()).isTrue();
            assertThat(response.currentLiveVideoId()).isEqualTo("video002");
            assertThat(response.lastCheckedAt()).isEqualTo(checkedAt);
            assertThat(response.recordEnabled()).isTrue();
            assertThat(response.lastRecordedVideoId()).isEqualTo("video003");
            assertThat(response.recordTitleKeywords()).isEqualTo("【ASMR】,【生配信】");
            assertThat(response.consecutiveDetectionFailures()).isZero();
            assertThat(response.createdAt()).isEqualTo(createdAt);
        }

        @Test
        @DisplayName("正常系：未通知・未チェック・未録画のチャンネルはnull項目を含んだまま変換される")
        void testMethod02() {
            MonitoredChannel channel = new MonitoredChannel("UCyyyyyyyy", "新規チャンネル");
            channel.setId(2L);

            MonitoredChannelResponse response = MonitoredChannelResponse.from(channel);

            assertThat(response.lastNotifiedVideoId()).isNull();
            assertThat(response.currentlyLive()).isFalse();
            assertThat(response.currentLiveVideoId()).isNull();
            assertThat(response.lastCheckedAt()).isNull();
            assertThat(response.recordEnabled()).isFalse();
            assertThat(response.lastRecordedVideoId()).isNull();
            assertThat(response.recordTitleKeywords()).isNull();
            assertThat(response.consecutiveDetectionFailures()).isZero();
            assertThat(response.lastDetectionSuccessAt()).isNull();
        }

        @Test
        @DisplayName("正常系：プラットフォームの表示名も詰める（画面側に対応表を持たせないため）")
        void testMethod03() {
            MonitoredChannel channel = new MonitoredChannel(
                    Platform.TWITCH, "12826", "テスト配信者", false, null);

            MonitoredChannelResponse response = MonitoredChannelResponse.from(channel);

            assertThat(response.platform()).isEqualTo(Platform.TWITCH);
            assertThat(response.platformLabel()).isEqualTo("Twitch");
        }

        @Test
        @DisplayName("正常系：プラットフォームを指定せず作られたチャンネルはYouTubeになる")
        void testMethod04() {
            MonitoredChannelResponse response =
                    MonitoredChannelResponse.from(new MonitoredChannel("UCyyyyyyyy", "新規チャンネル"));

            assertThat(response.platform()).isEqualTo(Platform.YOUTUBE);
            assertThat(response.platformLabel()).isEqualTo("YouTube");
        }
    }
}
