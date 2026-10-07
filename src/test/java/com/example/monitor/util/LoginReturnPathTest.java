package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoginReturnPath")
class LoginReturnPathTest {

    /**
     * 拒むケースは、受け付ける範囲が広い管理者（admin = true）で確かめる。管理者で拒むものは利用者でも拒む。
     * ログインに失敗したときも、未認証の入力を admin = true で照合している（LoggingAuthenticationFailureHandler）。
     */
    @Nested
    @DisplayName("validate()")
    class Validate {

        @Test
        @DisplayName("正常系：利用者の画面は利用者でも管理者でもそのまま返す")
        void testMethod01() {
            for (String page : List.of("/videos.html", "/my-channels.html", "/my-recordings.html")) {
                assertThat(LoginReturnPath.validate(page, false)).as("利用者：%s", page).isEqualTo(page);
                assertThat(LoginReturnPath.validate(page, true)).as("管理者：%s", page).isEqualTo(page);
            }
        }

        @Test
        @DisplayName("正常系：管理者の画面は管理者ならそのまま返す")
        void testMethod02() {
            assertThat(LoginReturnPath.validate("/tables.html", true)).isEqualTo("/tables.html");
            assertThat(LoginReturnPath.validate("/", true)).isEqualTo("/");
            assertThat(LoginReturnPath.validate("/player.html", true)).isEqualTo("/player.html");
        }

        @Test
        @DisplayName("正常系：/my 配下はクエリも含めてそのまま返す")
        void testMethod03() {
            assertThat(LoginReturnPath.validate("/my", false)).isEqualTo("/my");
            assertThat(LoginReturnPath.validate("/my/archive?genre=x", false)).isEqualTo("/my/archive?genre=x");
            assertThat(LoginReturnPath.validate("/my/watch/1", false)).isEqualTo("/my/watch/1");
        }

        @Test
        @DisplayName("異常系：利用者が管理者の画面を指定した場合はnullを返す")
        void testMethod04() {
            assertThat(LoginReturnPath.validate("/tables.html", false)).isNull();
            assertThat(LoginReturnPath.validate("/player.html", false)).isNull();
        }

        @Test
        @DisplayName("異常系：nullの場合はnullを返す")
        void testMethod05() {
            assertThat(LoginReturnPath.validate(null, true)).isNull();
        }

        @Test
        @DisplayName("異常系：2048文字を超える場合はnullを返す")
        void testMethod06() {
            String atLimit = "/my/" + "a".repeat(2044);
            // 拒む理由が長さだけであることを、1 文字少ない 2048 文字なら返ることで確かめる
            assertThat(LoginReturnPath.validate(atLimit, true)).isEqualTo(atLimit);
            assertThat(LoginReturnPath.validate(atLimit + "a", true)).isNull();
        }

        @Test
        @DisplayName("異常系：外部サイトのURL（スキーム付き・//で始まる）はnullを返す")
        void testMethod07() {
            assertThat(LoginReturnPath.validate("https://example.com/", true)).isNull();
            assertThat(LoginReturnPath.validate("//example.com", true)).isNull();
        }

        @Test
        @DisplayName("異常系：バックスラッシュを含む場合はnullを返す")
        void testMethod08() {
            assertThat(LoginReturnPath.validate("/my\\archive", true)).isNull();
            // ブラウザは \ を / として扱い、/\example.com を外部の //example.com へ解決する
            assertThat(LoginReturnPath.validate("/\\example.com", true)).isNull();
        }

        @Test
        @DisplayName("異常系：制御文字を含む場合はnullを返す")
        void testMethod09() {
            assertThat(LoginReturnPath.validate("/videos.html\r\n", true)).isNull();
            assertThat(LoginReturnPath.validate("/my/\tarchive", true)).isNull();
        }

        @Test
        @DisplayName("異常系：画面でないパス（API）は管理者でもnullを返す")
        void testMethod10() {
            assertThat(LoginReturnPath.validate("/api/my/channels", true)).isNull();
        }

        @Test
        @DisplayName("異常系：/my 配下でも . や .. のセグメントを含む場合はnullを返す")
        void testMethod11() {
            assertThat(LoginReturnPath.validate("/my/../tables.html", true)).isNull();
            assertThat(LoginReturnPath.validate("/my/./x", true)).isNull();
        }

        @Test
        @DisplayName("異常系：%2e で符号化した .. も復号してから拒む")
        void testMethod12() {
            // ブラウザは %2e も . として扱い、/tables.html へ解決する
            assertThat(LoginReturnPath.validate("/my/%2e%2e/tables.html", true)).isNull();
        }

        @Test
        @DisplayName("異常系：/my で始まるだけの別のパスはnullを返す")
        void testMethod13() {
            assertThat(LoginReturnPath.validate("/myx", true)).isNull();
        }

        @Test
        @DisplayName("正常系：端末の状態の画面（/system.html）は管理者ならそのまま返し、利用者なら null を返す")
        void testMethod14() {
            assertThat(LoginReturnPath.validate("/system.html", true)).isEqualTo("/system.html");
            assertThat(LoginReturnPath.validate("/system.html", false)).isNull();
        }
    }

    @Nested
    @DisplayName("isAdminPage()")
    class IsAdminPage {

        @Test
        @DisplayName("正常系：管理者の画面はtrueを返す")
        void testMethod01() {
            assertThat(LoginReturnPath.isAdminPage("/tables.html")).isTrue();
        }

        @Test
        @DisplayName("正常系：利用者の画面はfalseを返す")
        void testMethod02() {
            assertThat(LoginReturnPath.isAdminPage("/my/archive")).isFalse();
            assertThat(LoginReturnPath.isAdminPage("/my-channels.html")).isFalse();
        }

        @Test
        @DisplayName("正常系：端末の状態の画面（/system.html）は true を返す")
        void testMethod03() {
            assertThat(LoginReturnPath.isAdminPage("/system.html")).isTrue();
        }
    }
}
