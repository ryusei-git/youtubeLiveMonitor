package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("YouTubeChannelInputParser")
class YouTubeChannelInputParserTest {

    @Nested
    @DisplayName("normalize()")
    class Normalize {

        @Test
        @DisplayName("正常系：チャンネルID形式のURLからチャンネルIDを抜き出す")
        void testMethod01() {
            String result = YouTubeChannelInputParser.normalize(
                    "https://www.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow");

            assertThat(result).isEqualTo("UCSJ4gkVC6NrvII8umztf0Ow");
        }

        @Test
        @DisplayName("正常系：ハンドル形式のURLから@付きハンドルを抜き出す")
        void testMethod02() {
            String result = YouTubeChannelInputParser.normalize("https://www.youtube.com/@seldea");

            assertThat(result).isEqualTo("@seldea");
        }

        @Test
        @DisplayName("正常系：URL末尾にタブが付いていても抜き出せる")
        void testMethod03() {
            assertThat(YouTubeChannelInputParser.normalize("https://www.youtube.com/@seldea/streams"))
                    .isEqualTo("@seldea");
            assertThat(YouTubeChannelInputParser.normalize(
                    "https://www.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow/live")).isEqualTo("UCSJ4gkVC6NrvII8umztf0Ow");
        }

        @Test
        @DisplayName("正常系：クエリ文字列が付いていても抜き出せる")
        void testMethod04() {
            assertThat(YouTubeChannelInputParser.normalize("https://www.youtube.com/@seldea?si=abc"))
                    .isEqualTo("@seldea");
        }

        @Test
        @DisplayName("正常系：日本語ハンドルのURLはデコードして返す")
        void testMethod05() {
            // ブラウザからコピーすると日本語部分は百分率エンコードされている
            String result = YouTubeChannelInputParser.normalize(
                    "https://www.youtube.com/@%E3%81%97%E3%81%AE%E3%81%AE%E3%82%81%E3%82%81%E3%81%A8");

            assertThat(result).isEqualTo("@しののめめと");
        }

        @Test
        @DisplayName("正常系：URLでない場合は前後の空白を落としてそのまま返す")
        void testMethod06() {
            assertThat(YouTubeChannelInputParser.normalize("  @seldea  ")).isEqualTo("@seldea");
            // チャンネルIDは UC で始まる24文字
            assertThat(YouTubeChannelInputParser.normalize("UCdyqAaZDKHXg4Ahi7VENThQ"))
                    .isEqualTo("UCdyqAaZDKHXg4Ahi7VENThQ");
        }

        @Test
        @DisplayName("正常系：httpsやwwwが無いURLでも抜き出せる")
        void testMethod07() {
            assertThat(YouTubeChannelInputParser.normalize("youtube.com/@seldea")).isEqualTo("@seldea");
        }

        @Test
        @DisplayName("正常系：@を付け忘れた入力はハンドルとみなして@を補う")
        void testMethod09() {
            // 実際に2件発生した事故。@ が無いだけでチャンネルIDとみなすと、
            // /channel/{入力}/live が 404 になって監視が永久に機能しなくなる
            assertThat(YouTubeChannelInputParser.normalize("ShiroganeNoel")).isEqualTo("@ShiroganeNoel");
            assertThat(YouTubeChannelInputParser.normalize("mikenekoko")).isEqualTo("@mikenekoko");
        }

        @Test
        @DisplayName("正常系：チャンネルIDの形をしていれば@を付けずそのまま返す")
        void testMethod10() {
            assertThat(YouTubeChannelInputParser.normalize("UC7Bb4I4tUzj1ftJI918QJsA"))
                    .isEqualTo("UC7Bb4I4tUzj1ftJI918QJsA");
            // 記号を含むチャンネルIDもそのまま通す
            assertThat(YouTubeChannelInputParser.normalize("UC_v-Rg-FYBUfkF4GLcMDEcg"))
                    .isEqualTo("UC_v-Rg-FYBUfkF4GLcMDEcg");
        }

        @Test
        @DisplayName("正常系：UCで始まっていても長さが違えばハンドルとして扱う")
        void testMethod11() {
            // 「UCで始まる」だけを条件にすると、UC で始まるハンドルを取りこぼす
            assertThat(YouTubeChannelInputParser.normalize("UCchannelName")).isEqualTo("@UCchannelName");
        }

        @Test
        @DisplayName("正常系：既に@が付いている入力は二重に付けない")
        void testMethod12() {
            assertThat(YouTubeChannelInputParser.normalize("@ShiroganeNoel")).isEqualTo("@ShiroganeNoel");
        }

        @Test
        @DisplayName("異常系：空文字やnullはIllegalArgumentExceptionになる")
        void testMethod08() {
            assertThatThrownBy(() -> YouTubeChannelInputParser.normalize(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> YouTubeChannelInputParser.normalize("   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> YouTubeChannelInputParser.normalize(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
