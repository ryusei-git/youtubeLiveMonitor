package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DiscordWebhookUrl")
class DiscordWebhookUrlTest {

    @Nested
    @DisplayName("isValid()")
    class IsValid {

        @Test
        @DisplayName("正常系：discord.comのWebhookのURLを受け付ける")
        void testMethod01() {
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/123456789012345678/abcDEF_123-xyz"))
                    .isTrue();
        }

        @Test
        @DisplayName("正常系：discordapp.comのWebhookのURLを受け付ける")
        void testMethod02() {
            assertThat(DiscordWebhookUrl.isValid("https://discordapp.com/api/webhooks/123456789012345678/abcDEF_123-xyz"))
                    .isTrue();
        }

        @Test
        @DisplayName("正常系：ちょうど上限の長さなら受け付ける")
        void testMethod03() {
            String prefix = "https://discord.com/api/webhooks/1/";
            String url = prefix + "a".repeat(DiscordWebhookUrl.MAX_LENGTH - prefix.length());

            assertThat(url).hasSize(512);
            assertThat(DiscordWebhookUrl.isValid(url)).isTrue();
        }

        @Test
        @DisplayName("異常系：上限より1文字長いものは断る")
        void testMethod04() {
            String prefix = "https://discord.com/api/webhooks/1/";
            String url = prefix + "a".repeat(DiscordWebhookUrl.MAX_LENGTH - prefix.length());

            assertThat(DiscordWebhookUrl.isValid(url + "a")).isFalse();
        }

        @Test
        @DisplayName("異常系：nullと空文字は断る")
        void testMethod05() {
            assertThat(DiscordWebhookUrl.isValid(null)).isFalse();
            assertThat(DiscordWebhookUrl.isValid("")).isFalse();
        }

        @Test
        @DisplayName("異常系：httpは断る")
        void testMethod06() {
            assertThat(DiscordWebhookUrl.isValid("http://discord.com/api/webhooks/1/token")).isFalse();
        }

        @Test
        @DisplayName("異常系：Discord以外のホストは断る")
        void testMethod07() {
            // 任意の宛先を受け付けると、サーバーから好きな宛先へ POST させられる（DiscordWebhookUrl のクラスの JavaDoc）
            assertThat(DiscordWebhookUrl.isValid("https://example.com/api/webhooks/1/token")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com.example.com/api/webhooks/1/token")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://ptb.discord.com/api/webhooks/1/token")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://canary.discord.com/api/webhooks/1/token")).isFalse();
        }

        @Test
        @DisplayName("異常系：Discordの名前を含めて別のホストへ向けるURLは断る")
        void testMethod08() {
            // 任意の宛先を受け付けると、サーバーから好きな宛先へ POST させられる（DiscordWebhookUrl のクラスの JavaDoc）
            assertThat(DiscordWebhookUrl.isValid("https://discord.com@example.com/api/webhooks/1/token")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://example.com/https://discord.com/api/webhooks/1/token"))
                    .isFalse();
        }

        @Test
        @DisplayName("異常系：クエリ・フラグメント・ポートを付けたものは断る")
        void testMethod09() {
            // 崩れた URL から要求を組み立てると例外の文言に URL（トークン）が入るので、形の段階で断つ
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/1/token?wait=true")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/1/token?thread_id=1")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/1/token#x")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com:443/api/webhooks/1/token")).isFalse();
        }

        @Test
        @DisplayName("異常系：Webhookのパスの形でないものは断る")
        void testMethod10() {
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/v10/webhooks/1/token")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/abc/token")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/1/")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/1/token/extra")).isFalse();
        }

        @Test
        @DisplayName("異常系：空白・改行・ASCII以外の文字を含むものは断る")
        void testMethod11() {
            // 崩れた URL から要求を組み立てると例外の文言に URL（トークン）が入るので、形の段階で断つ
            assertThat(DiscordWebhookUrl.isValid(" https://discord.com/api/webhooks/1/token")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/1/token\n")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/1/to ken")).isFalse();
            assertThat(DiscordWebhookUrl.isValid("https://discord.com/api/webhooks/1/トークン")).isFalse();
        }
    }
}
