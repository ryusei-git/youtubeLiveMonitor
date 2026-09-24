package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UpcomingStreamResponse")
class UpcomingStreamResponseTest {

    /** 範囲は呼び出した時刻から求めるため、開始予定時刻は各テストで {@code LocalDateTime.now()} からの相対で渡す。 */
    private static MonitoredChannel channelWithUpcoming(String videoId, LocalDateTime scheduledStartTime) {
        MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
        channel.setUpcomingVideoId(videoId);
        channel.setUpcomingScheduledStartTime(scheduledStartTime);
        return channel;
    }

    @Nested
    @DisplayName("listWithinWindow()")
    class ListWithinWindow {

        @Test
        @DisplayName("正常系：7日以内の予定が開始予定の早い順に並ぶ")
        void testMethod01() {
            LocalDateTime now = LocalDateTime.now();
            List<MonitoredChannel> channels = List.of(
                    channelWithUpcoming("day3", now.plusDays(3)),
                    channelWithUpcoming("hour1", now.plusHours(1)),
                    channelWithUpcoming("day6", now.plusDays(6)));

            assertThat(UpcomingStreamResponse.listWithinWindow(channels))
                    .extracting(UpcomingStreamResponse::videoId)
                    .containsExactly("hour1", "day3", "day6");
        }

        @Test
        @DisplayName("正常系：開始予定を過ぎてまだ始まっていない（遅れている）予定も含め、先頭に並ぶ")
        void testMethod02() {
            LocalDateTime now = LocalDateTime.now();
            List<MonitoredChannel> channels = List.of(
                    channelWithUpcoming("future", now.plusHours(2)),
                    channelWithUpcoming("late", now.minusMinutes(30)));

            assertThat(UpcomingStreamResponse.listWithinWindow(channels))
                    .extracting(UpcomingStreamResponse::videoId)
                    .containsExactly("late", "future");
        }

        @Test
        @DisplayName("正常系：配信予定の動画IDが無いチャンネルは除く")
        void testMethod03() {
            // 開始予定時刻は範囲内にしておき、動画 ID が無いことだけで除かれることを確かめる
            LocalDateTime now = LocalDateTime.now();
            List<MonitoredChannel> channels = List.of(
                    channelWithUpcoming(null, now.plusHours(1)),
                    channelWithUpcoming("kept", now.plusHours(2)));

            assertThat(UpcomingStreamResponse.listWithinWindow(channels))
                    .extracting(UpcomingStreamResponse::videoId)
                    .containsExactly("kept");
        }

        @Test
        @DisplayName("異常系：開始予定時刻を取得できなかった予定は、範囲内か判断できないので除く")
        void testMethod04() {
            LocalDateTime now = LocalDateTime.now();
            List<MonitoredChannel> channels = List.of(
                    channelWithUpcoming("unknown", null),
                    channelWithUpcoming("kept", now.plusHours(1)));

            assertThat(UpcomingStreamResponse.listWithinWindow(channels))
                    .extracting(UpcomingStreamResponse::videoId)
                    .containsExactly("kept");
        }

        @Test
        @DisplayName("正常系：開始予定が7日より先の予定は除く")
        void testMethod05() {
            // 境目の前後 1 時間で確かめる。ちょうど 7 日後にすると、テストの実行時間しだいで結果が変わる
            LocalDateTime now = LocalDateTime.now();
            List<MonitoredChannel> channels = List.of(
                    channelWithUpcoming("beyond", now.plusDays(7).plusHours(1)),
                    channelWithUpcoming("within", now.plusDays(7).minusHours(1)));

            assertThat(UpcomingStreamResponse.listWithinWindow(channels))
                    .extracting(UpcomingStreamResponse::videoId)
                    .containsExactly("within");
        }

        @Test
        @DisplayName("正常系：空の入力には空の一覧を返す")
        void testMethod06() {
            assertThat(UpcomingStreamResponse.listWithinWindow(List.of())).isEmpty();
        }
    }
}
