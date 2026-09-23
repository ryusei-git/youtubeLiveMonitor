package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("YouTubeWatchUrl")
class YouTubeWatchUrlTest {

    @Nested
    @DisplayName("of()")
    class Of {

        @Test
        @DisplayName("正常系：動画IDからYouTubeの視聴ページURLを組み立てる")
        void testMethod01() {
            assertThat(YouTubeWatchUrl.of("abcdefg1234"))
                    .isEqualTo("https://www.youtube.com/watch?v=abcdefg1234");
        }
    }
}
