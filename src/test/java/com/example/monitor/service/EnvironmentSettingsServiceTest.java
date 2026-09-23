package com.example.monitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EnvironmentSettingsService")
class EnvironmentSettingsServiceTest {

    @Nested
    @DisplayName("updateEnvFile()")
    class UpdateEnvFile {

        @Test
        @DisplayName("正常系：既存キーの値だけ書き換え、他の行はそのまま残す")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, """
                    # コメント行
                    YOUTUBE_API_KEY=old-key
                    DISCORD_WEBHOOK_URL=https://old.example/webhook
                    """);
            EnvironmentSettingsService service =
                    new EnvironmentSettingsService(envFile.toString(), tempDir.resolve(".env.example").toString());

            service.updateEnvFile(Map.of("YOUTUBE_API_KEY", "new-key"));

            List<String> lines = Files.readAllLines(envFile);
            assertThat(lines).containsExactly(
                    "# コメント行",
                    "YOUTUBE_API_KEY=new-key",
                    "DISCORD_WEBHOOK_URL=https://old.example/webhook");
        }

        @Test
        @DisplayName("正常系：元のファイルに無いキーは末尾に追記する")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "YOUTUBE_API_KEY=old-key\n");
            EnvironmentSettingsService service =
                    new EnvironmentSettingsService(envFile.toString(), tempDir.resolve(".env.example").toString());

            service.updateEnvFile(Map.of("MONITOR_INTERVAL_SECONDS", "60"));

            List<String> lines = Files.readAllLines(envFile);
            assertThat(lines).containsExactly("YOUTUBE_API_KEY=old-key", "MONITOR_INTERVAL_SECONDS=60");
        }

        @Test
        @DisplayName("正常系：.envが無い場合は.env.exampleを土台にする")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            Path envFile = tempDir.resolve(".env");
            Path exampleFile = tempDir.resolve(".env.example");
            Files.writeString(exampleFile, """
                    # 説明コメント
                    YOUTUBE_API_KEY=your_youtube_api_key_here
                    """);
            EnvironmentSettingsService service =
                    new EnvironmentSettingsService(envFile.toString(), exampleFile.toString());

            service.updateEnvFile(Map.of("YOUTUBE_API_KEY", "actual-key"));

            assertThat(Files.readAllLines(envFile)).containsExactly(
                    "# 説明コメント",
                    "YOUTUBE_API_KEY=actual-key");
            // 土台にしたファイル自体は変更しない
            assertThat(Files.readString(exampleFile)).contains("your_youtube_api_key_here");
        }

        @Test
        @DisplayName("正常系：.envも.env.exampleも無い場合は新規作成する")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            Path envFile = tempDir.resolve(".env");
            EnvironmentSettingsService service = new EnvironmentSettingsService(
                    envFile.toString(), tempDir.resolve(".env.example").toString());

            service.updateEnvFile(Map.of("MONITOR_RECORDING_DIRECTORY", "recordings"));

            assertThat(Files.readAllLines(envFile)).containsExactly("MONITOR_RECORDING_DIRECTORY=recordings");
        }

        @Test
        @DisplayName("正常系：更新対象を指定しなければファイルの内容は変わらない")
        void testMethod05(@TempDir Path tempDir) throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "YOUTUBE_API_KEY=old-key\n");
            EnvironmentSettingsService service =
                    new EnvironmentSettingsService(envFile.toString(), tempDir.resolve(".env.example").toString());

            service.updateEnvFile(Map.of());

            assertThat(Files.readAllLines(envFile)).containsExactly("YOUTUBE_API_KEY=old-key");
        }

        @Test
        @DisplayName("異常系：書き込み先がディレクトリの場合は例外を投げる")
        void testMethod06(@TempDir Path tempDir) throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.createDirectory(envFile);
            EnvironmentSettingsService service =
                    new EnvironmentSettingsService(envFile.toString(), tempDir.resolve(".env.example").toString());

            assertThatThrownBy(() -> service.updateEnvFile(Map.of("YOUTUBE_API_KEY", "x")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
