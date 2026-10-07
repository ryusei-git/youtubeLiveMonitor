package com.example.monitor.service;

import com.example.monitor.service.HubAppRegistry.HubApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * サービスの一覧のファイルの読み方と、作業フォルダーからサービスを引く判定を確かめる。
 *
 * <p>フォルダーは {@code @TempDir} の下に作る。{@code /x/catch} のような決め打ちは Windows では
 * 絶対パスにならず（ドライブ名が無い）、作業端末でテストが落ちるため。
 */
@DisplayName("HubAppRegistry")
class HubAppRegistryTest {

    /**
     * 一覧のファイルを書く。JSON は Jackson で書き出す（Windows のフォルダーの {@code \} を
     * エスケープするため）。要素に {@code null} を渡すと、ファイルにも {@code null} の要素として書く。
     */
    private static Path write(Path dir, Object... entries) throws IOException {
        Path file = dir.resolve("hub-apps.json");
        Files.writeString(file, JsonMapper.shared().writeValueAsString(Arrays.asList(entries)));
        return file;
    }

    @Nested
    @DisplayName("load()")
    class Load {

        @Test
        @DisplayName("正常系：ファイルの順に読み、空白だけの url・launch は null にし、folder は正規化する")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            Path file = write(tempDir,
                    Map.of("name", "キャッチ！", "url", "http://ryusei:3000/",
                            "folder", tempDir.resolve("a").resolve("..").resolve("catch").toString(),
                            "launch", "npm start"),
                    Map.of("name", "Grok Discord Bot", "url", " ", "folder", tempDir.resolve("bot").toString(),
                            "launch", " "));

            List<HubApp> result = HubAppRegistry.load(file);

            assertThat(result).containsExactly(
                    new HubApp("キャッチ！", "http://ryusei:3000/", tempDir.resolve("catch"), "npm start"),
                    new HubApp("Grok Discord Bot", null, tempDir.resolve("bot"), null));
        }

        @Test
        @DisplayName("正常系：ファイルが無ければ空")
        void testMethod02(@TempDir Path tempDir) {
            assertThat(HubAppRegistry.load(tempDir.resolve("missing.json"))).isEmpty();
        }

        @Test
        @DisplayName("異常系：JSON として壊れていれば、例外を投げずに空")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("hub-apps.json");
            Files.writeString(file, "[{\"name\": ");

            assertThat(HubAppRegistry.load(file)).isEmpty();
        }

        @Test
        @DisplayName("異常系：名前が空・相対パスの folder・javascript: や // で始まる url・null の要素だけを飛ばし、ほかは読む")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            String folder = tempDir.resolve("app").toString();
            Path file = write(tempDir,
                    Map.of("name", "先頭", "folder", folder),
                    Map.of("name", " ", "folder", folder),
                    Map.of("name", "相対パス", "folder", "relative/dir"),
                    Map.of("name", "スクリプト", "url", "javascript:alert(1)", "folder", folder),
                    Map.of("name", "ほかのホスト", "url", "//example.com", "folder", folder),
                    null,
                    Map.of("name", "末尾", "url", "/index.html", "folder", folder));

            List<HubApp> result = HubAppRegistry.load(file);

            assertThat(result).extracting(HubApp::name).containsExactly("先頭", "末尾");
        }
    }

    @Nested
    @DisplayName("appFor()")
    class AppFor {

        /** {@code x/catch} と、その中に入れ子の {@code x/catch/inner} を並べた一覧。 */
        private static HubAppRegistry registry(Path tempDir) throws IOException {
            Path file = write(tempDir,
                    Map.of("name", "キャッチ！", "folder", tempDir.resolve("x").resolve("catch").toString()),
                    Map.of("name", "内側", "folder", tempDir.resolve("x").resolve("catch").resolve("inner").toString()));
            return new HubAppRegistry(file.toString());
        }

        @Test
        @DisplayName("正常系：作業フォルダーがサービスのフォルダーの中なら、そのサービス")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            Path workingDirectory = tempDir.resolve("x").resolve("catch").resolve("src");

            assertThat(registry(tempDir).appFor(workingDirectory)).map(HubApp::name).contains("キャッチ！");
        }

        @Test
        @DisplayName("正常系：入れ子のフォルダーなら、内側（フォルダーの長い方）のサービス")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            Path workingDirectory = tempDir.resolve("x").resolve("catch").resolve("inner").resolve("bin");

            assertThat(registry(tempDir).appFor(workingDirectory)).map(HubApp::name).contains("内側");
        }

        @Test
        @DisplayName("正常系：どのサービスのフォルダーの中でもなければ空")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            assertThat(registry(tempDir).appFor(tempDir.resolve("y"))).isEmpty();
        }

        @Test
        @DisplayName("正常系：名前の先頭が同じだけのフォルダー（x/catch2）は x/catch の中とみなさない")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            assertThat(registry(tempDir).appFor(tempDir.resolve("x").resolve("catch2"))).isEmpty();
        }
    }
}
