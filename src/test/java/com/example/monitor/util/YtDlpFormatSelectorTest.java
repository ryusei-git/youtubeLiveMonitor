package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("YtDlpFormatSelector")
class YtDlpFormatSelectorTest {

    @Nested
    @DisplayName("of()")
    class Of {

        @Test
        @DisplayName("正常系：上限を指定するとその高さ以下に絞る指定を返す")
        void testMethod01() {
            assertThat(YtDlpFormatSelector.of(1080))
                    .isEqualTo("bestvideo[height<=1080]+bestaudio/best[height<=1080]/bestvideo+bestaudio/best");
        }

        @Test
        @DisplayName("正常系：0は「上限なし」として解像度の条件を付けない")
        void testMethod02() {
            // 画質を落としたくないという要望から、既定値の 0 は上限なしを意味する
            assertThat(YtDlpFormatSelector.of(0)).isEqualTo("bestvideo+bestaudio/best");
        }

        @Test
        @DisplayName("正常系：負の値も「上限なし」として扱う")
        void testMethod03() {
            assertThat(YtDlpFormatSelector.of(-1)).isEqualTo("bestvideo+bestaudio/best");
        }
    }
}
