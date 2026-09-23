package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UrlHostMatcher")
class UrlHostMatcherTest {

    @Nested
    @DisplayName("matchesAnyDomain()")
    class MatchesAnyDomain {

        @Test
        @DisplayName("正常系：ホストが一致すればtrueを返す")
        void testMethod01() {
            assertThat(UrlHostMatcher.matchesAnyDomain("https://youtube.com/watch?v=abc", "youtube.com"))
                    .isTrue();
        }

        @Test
        @DisplayName("正常系：サブドメイン付き（www / m）も一致とみなす")
        void testMethod02() {
            assertThat(UrlHostMatcher.matchesAnyDomain("https://www.youtube.com/watch?v=abc", "youtube.com"))
                    .isTrue();
            assertThat(UrlHostMatcher.matchesAnyDomain("https://m.youtube.com/watch?v=abc", "youtube.com"))
                    .isTrue();
        }

        @Test
        @DisplayName("正常系：複数のドメインのいずれかに一致すればtrueを返す")
        void testMethod03() {
            assertThat(UrlHostMatcher.matchesAnyDomain("https://youtu.be/abc", "youtube.com", "youtu.be"))
                    .isTrue();
        }

        @Test
        @DisplayName("正常系：スキームを省略した入力でも判定できる")
        void testMethod04() {
            // 利用者はアドレスバーからコピーするとは限らない
            assertThat(UrlHostMatcher.matchesAnyDomain("www.youtube.com/watch?v=abc", "youtube.com"))
                    .isTrue();
        }

        @Test
        @DisplayName("正常系：大文字で入力されても一致する")
        void testMethod05() {
            assertThat(UrlHostMatcher.matchesAnyDomain("https://WWW.YouTube.COM/watch?v=abc", "youtube.com"))
                    .isTrue();
        }

        @Test
        @DisplayName("異常系：ドメインがパスに含まれるだけの別サイトは一致しない")
        void testMethod06() {
            // contains で判定すると誤って一致してしまう形
            assertThat(UrlHostMatcher.matchesAnyDomain("https://example.com/youtube.com/watch", "youtube.com"))
                    .isFalse();
        }

        @Test
        @DisplayName("異常系：後ろに別のドメインを繋げた紛らわしいホストは一致しない")
        void testMethod07() {
            assertThat(UrlHostMatcher.matchesAnyDomain("https://youtube.com.example.com/watch", "youtube.com"))
                    .isFalse();
            assertThat(UrlHostMatcher.matchesAnyDomain("https://notyoutube.com/watch", "youtube.com"))
                    .isFalse();
        }

        @Test
        @DisplayName("異常系：空やnullはfalseを返す")
        void testMethod08() {
            assertThat(UrlHostMatcher.matchesAnyDomain(null, "youtube.com")).isFalse();
            assertThat(UrlHostMatcher.matchesAnyDomain("   ", "youtube.com")).isFalse();
        }

        @Test
        @DisplayName("異常系：URLとして解釈できない文字列はfalseを返す")
        void testMethod09() {
            assertThat(UrlHostMatcher.matchesAnyDomain("ただの文字列 です", "youtube.com")).isFalse();
        }
    }
}
