package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FileNameUtils")
class FileNameUtilsTest {

    @Nested
    @DisplayName("stripExtension()")
    class StripExtension {

        @Test
        @DisplayName("正常系：指定した拡張子で終わる場合は拡張子を除去する")
        void testMethod01() {
            String result = FileNameUtils.stripExtension("UCxxxxxxxx.log", ".log");

            assertThat(result).isEqualTo("UCxxxxxxxx");
        }

        @Test
        @DisplayName("正常系：指定した拡張子で終わらない場合はそのまま返す")
        void testMethod02() {
            String result = FileNameUtils.stripExtension("UCxxxxxxxx.txt", ".log");

            assertThat(result).isEqualTo("UCxxxxxxxx.txt");
        }

        @Test
        @DisplayName("正常系：ファイル名が拡張子のみの場合は空文字になる")
        void testMethod03() {
            String result = FileNameUtils.stripExtension(".log", ".log");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("異常系：ファイル名がnullの場合はNullPointerExceptionが発生する")
        void testMethod04() {
            assertThatThrownBy(() -> FileNameUtils.stripExtension(null, ".log"))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
