package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.platform.Platform;
import com.example.monitor.util.StreamLinkUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SubscribedChannelResponse")
class SubscribedChannelResponseTest {

    private static UserSubscription subscriptionTo(MonitoredChannel channel) {
        return UserSubscription.builder().channel(channel).build();
    }

    @Nested
    @DisplayName("from(UserSubscription)")
    class From {

        @Test
        @DisplayName("正常系：録画件数は0になる（件数を返さない応答のために録画を数えない）")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");

            SubscribedChannelResponse response = SubscribedChannelResponse.from(subscriptionTo(channel));

            assertThat(response.recordingCount()).isZero();
        }

        @Test
        @DisplayName("正常系：アイコンURLはチャンネルの値を返す")
        void testMethod02() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setChannelIconUrl("https://yt3.ggpht.com/icon=s88");

            SubscribedChannelResponse response = SubscribedChannelResponse.from(subscriptionTo(channel));

            assertThat(response.channelIconUrl()).isEqualTo("https://yt3.ggpht.com/icon=s88");
        }
    }

    /** チャンネル URL は、管理者の一覧などと同じ {@code StreamLinkUtils.channelUrl()} の結果と一致することも確かめる。 */
    @Nested
    @DisplayName("from(UserSubscription, long)")
    class FromWithCount {

        @Test
        @DisplayName("正常系：渡した録画件数がそのまま入る")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");

            SubscribedChannelResponse response = SubscribedChannelResponse.from(subscriptionTo(channel), 12L);

            assertThat(response.recordingCount()).isEqualTo(12L);
        }

        @Test
        @DisplayName("正常系：YouTubeのチャンネルURLはチャンネルIDから組み立てる")
        void testMethod02() {
            MonitoredChannel channel = new MonitoredChannel(Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", false, null);

            SubscribedChannelResponse response = SubscribedChannelResponse.from(subscriptionTo(channel), 0L);

            assertThat(response.channelUrl())
                    .isEqualTo("https://www.youtube.com/channel/UCxxxxxxxx")
                    .isEqualTo(StreamLinkUtils.channelUrl(Platform.YOUTUBE, "UCxxxxxxxx", null));
        }

        @Test
        @DisplayName("正常系：TwitchのチャンネルURLはログイン名から組み立てる")
        void testMethod03() {
            MonitoredChannel channel = new MonitoredChannel(Platform.TWITCH, "12826", "テスト配信者", false, null);
            channel.setChannelLogin("test_streamer");

            SubscribedChannelResponse response = SubscribedChannelResponse.from(subscriptionTo(channel), 0L);

            assertThat(response.channelUrl())
                    .isEqualTo("https://www.twitch.tv/test_streamer")
                    .isEqualTo(StreamLinkUtils.channelUrl(Platform.TWITCH, "12826", "test_streamer"));
        }

        @Test
        @DisplayName("異常系：Twitchでログイン名を取得できていない場合はチャンネルURLをnullにする（ユーザーIDで代用しない）")
        void testMethod04() {
            MonitoredChannel channel = new MonitoredChannel(Platform.TWITCH, "12826", "テスト配信者", false, null);

            SubscribedChannelResponse response = SubscribedChannelResponse.from(subscriptionTo(channel), 0L);

            assertThat(response.channelUrl())
                    .isEqualTo(StreamLinkUtils.channelUrl(Platform.TWITCH, "12826", null))
                    .isNull();
        }
    }
}
