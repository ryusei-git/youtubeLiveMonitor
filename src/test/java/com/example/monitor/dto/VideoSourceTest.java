package com.example.monitor.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VideoSource")
class VideoSourceTest {

    @Nested
    @DisplayName("parse()")
    class Parse {

        @Test
        @DisplayName("正常系：YouTubeの出力を各項目に分解する")
        void testMethod01() {
            String printed =
                    "youtube|aqz-KE-bpKQ|UCSMOQeBJ2RAnuFungnQOxLg|@BlenderOfficial|was_live|Big Buck Bunny\n";

            VideoSource source = VideoSource.parse(printed).orElseThrow();

            assertThat(source.extractor()).isEqualTo("youtube");
            assertThat(source.videoId()).isEqualTo("aqz-KE-bpKQ");
            assertThat(source.channelId()).isEqualTo("UCSMOQeBJ2RAnuFungnQOxLg");
            assertThat(source.uploaderId()).isEqualTo("@BlenderOfficial");
            assertThat(source.title()).isEqualTo("Big Buck Bunny");
        }

        @Test
        @DisplayName("正常系：取得できなかった項目（NA）はnullとして扱う")
        void testMethod02() {
            // Twitch の VOD では channel_id が取得できず、文字列 "NA" が返る（null ではない）。
            // そのまま持ち回ると "NA" というチャンネルIDを DB と突き合わせにいくことになる
            String printed = "twitch:vod|v2879280908|NA|caedrel|not_live|配信のタイトル";

            VideoSource source = VideoSource.parse(printed).orElseThrow();

            assertThat(source.extractor()).isEqualTo("twitch:vod");
            assertThat(source.videoId()).isEqualTo("v2879280908");
            assertThat(source.channelId()).isNull();
            assertThat(source.uploaderId()).isEqualTo("caedrel");
        }

        @Test
        @DisplayName("正常系：空文字の項目もnullとして扱う")
        void testMethod03() {
            VideoSource source = VideoSource.parse("youtube|abc123||@foo|was_live|タイトル").orElseThrow();

            assertThat(source.channelId()).isNull();
        }

        @Test
        @DisplayName("正常系：タイトルに区切り文字が含まれていても最後の項目としてまとめて取る")
        void testMethod04() {
            // 「Foo | Bar」のようなタイトルは珍しくない。自由入力の項目を末尾に置いているのはこのため
            VideoSource source = VideoSource.parse(
                    "youtube|abc123|UCxxxxxxxx|@foo|was_live|第1回 | 前編 | おまけ").orElseThrow();

            assertThat(source.title()).isEqualTo("第1回 | 前編 | おまけ");
        }

        @Test
        @DisplayName("正常系：前後の空行があっても解析できる")
        void testMethod05() {
            VideoSource source =
                    VideoSource.parse("\n\nyoutube|abc123|UCxxxxxxxx|@foo|was_live|タイトル\n").orElseThrow();

            assertThat(source.videoId()).isEqualTo("abc123");
        }

        @Test
        @DisplayName("異常系：動画IDが取得できない場合は解析失敗として扱う")
        void testMethod06() {
            // 保存先のファイル名すら決められないため、先へ進めてはいけない
            assertThat(VideoSource.parse("youtube|NA|UCxxxxxxxx|@foo|was_live|タイトル"))
                    .isEqualTo(Optional.empty());
        }

        @Test
        @DisplayName("異常系：項目数が足りない出力は解析失敗として扱う")
        void testMethod07() {
            assertThat(VideoSource.parse("youtube|abc123")).isEqualTo(Optional.empty());
        }

        @Test
        @DisplayName("異常系：空やnullは解析失敗として扱う")
        void testMethod08() {
            assertThat(VideoSource.parse(null)).isEqualTo(Optional.empty());
            assertThat(VideoSource.parse("   ")).isEqualTo(Optional.empty());
        }
    }
}
