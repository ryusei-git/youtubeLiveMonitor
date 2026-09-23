package com.example.monitor.util;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class YouTubeFeedParserTest {
    @Nested class Parse {
        @Test @DisplayName("正常系：チャンネルIDを照合し動画IDから視聴URLとサムネイルを取得する")
        void testMethod01() {
            String xml = """
                <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
                  <entry><yt:videoId>abcdefghijk</yt:videoId><yt:channelId>UCabcdefghijklmnopqrstuv</yt:channelId><title>A &amp; B</title><published>2026-09-23T01:00:00Z</published></entry>
                  <entry><yt:videoId>12345678901</yt:videoId><yt:channelId>other</yt:channelId><published>2026-09-23T01:00:00Z</published></entry>
                </feed>
                """;
            var videos = YouTubeFeedParser.parse(xml, "UCabcdefghijklmnopqrstuv");
            assertThat(videos).hasSize(1);
            assertThat(videos.getFirst().watchUrl()).isEqualTo("https://www.youtube.com/watch?v=abcdefghijk");
            assertThat(videos.getFirst().title()).isEqualTo("A & B");
        }
        @Test @DisplayName("異常系：HTMLのエラーページを新着なしとして処理しない")
        void testMethod02() {
            assertThatThrownBy(() -> YouTubeFeedParser.parse("<html>error</html>", "channel"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
