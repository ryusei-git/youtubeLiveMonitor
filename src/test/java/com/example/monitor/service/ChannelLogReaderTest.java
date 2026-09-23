package com.example.monitor.service;

import com.example.monitor.dto.LogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("ChannelLogReader")
class ChannelLogReaderTest {

    @Nested
    @DisplayName("listChannelsWithLogs()")
    class ListChannelsWithLogs {

        @Test
        @DisplayName("正常系：ログディレクトリが存在しない場合は空リストを返す")
        void testMethod01(@TempDir Path tempDir) {
            ChannelLogReader reader = new ChannelLogReader(tempDir.resolve("not-exist").toString());

            List<String> result = reader.listChannelsWithLogs();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系：チャンネルログファイルの拡張子を除いたIDを昇順で返す")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCbbbbbbbb.log"), "");
            Files.writeString(tempDir.resolve("UCaaaaaaaa.log"), "");

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<String> result = reader.listChannelsWithLogs();

            assertThat(result).containsExactly("UCaaaaaaaa", "UCbbbbbbbb");
        }

        @Test
        @DisplayName("正常系：ローテーション済みの日付付きファイルは除外される")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), "");
            Files.writeString(tempDir.resolve("UCxxxxxxxx.2026-09-12.log"), "");

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<String> result = reader.listChannelsWithLogs();

            assertThat(result).containsExactly("UCxxxxxxxx");
        }

        @Test
        @DisplayName("正常系：system.logは除外される")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), "");
            Files.writeString(tempDir.resolve("system.log"), "");

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<String> result = reader.listChannelsWithLogs();

            assertThat(result).containsExactly("UCxxxxxxxx");
        }
    }

    @Nested
    @DisplayName("readChannelLog()")
    class ReadChannelLog {

        @Test
        @DisplayName("正常系：ファイルが存在しない場合は空リストを返す")
        void testMethod01(@TempDir Path tempDir) {
            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());

            List<LogEntry> result = reader.readChannelLog("UCxxxxxxxx", 200, null).entries();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系：ログ行を時刻・レベル・出力元・本文に分解する")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"),
                    "2026-09-13 10:00:00 [INFO] c.e.m.service.Foo - 配信を検知しました\n");

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<LogEntry> result = reader.readChannelLog("UCxxxxxxxx", 200, null).entries();

            assertThat(result).hasSize(1);
            LogEntry entry = result.get(0);
            assertThat(entry.timestamp()).isEqualTo("2026-09-13 10:00:00");
            assertThat(entry.level()).isEqualTo("INFO");
            assertThat(entry.loggerName()).isEqualTo("c.e.m.service.Foo");
            assertThat(entry.message()).isEqualTo("配信を検知しました");
        }

        @Test
        @DisplayName("正常系：limitを超える行数がある場合は新しい方からlimit件だけ返す")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            String lines = """
                    2026-09-13 10:00:01 [INFO] c.e.m.Foo - 1件目
                    2026-09-13 10:00:02 [INFO] c.e.m.Foo - 2件目
                    2026-09-13 10:00:03 [INFO] c.e.m.Foo - 3件目
                    """;
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), lines);

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<LogEntry> result = reader.readChannelLog("UCxxxxxxxx", 2, null).entries();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).message()).isEqualTo("2件目");
            assertThat(result.get(1).message()).isEqualTo("3件目");
        }

        @Test
        @DisplayName("正常系：パターンに一致しない行は直前のエントリのメッセージに連結される")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            String lines = """
                    2026-09-13 10:00:00 [ERROR] c.e.m.Foo - 通知に失敗しました
                    \tat com.example.monitor.Foo.bar(Foo.java:10)
                    \tat com.example.monitor.Foo.baz(Foo.java:20)
                    """;
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), lines);

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<LogEntry> result = reader.readChannelLog("UCxxxxxxxx", 200, null).entries();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).message()).isEqualTo(
                    "通知に失敗しました\n\tat com.example.monitor.Foo.bar(Foo.java:10)\n\tat com.example.monitor.Foo.baz(Foo.java:20)");
        }

        @Test
        @DisplayName("正常系：ファイル先頭がパターンに一致しない場合はnull項目のエントリとして扱う")
        void testMethod05(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), "壊れた先頭行\n");

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<LogEntry> result = reader.readChannelLog("UCxxxxxxxx", 200, null).entries();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).timestamp()).isNull();
            assertThat(result.get(0).message()).isEqualTo("壊れた先頭行");
        }

        @Test
        @DisplayName("正常系：レベルを指定すると該当する行だけに絞り込まれる")
        void testMethod06(@TempDir Path tempDir) throws IOException {
            String lines = """
                    2026-09-13 10:00:01 [INFO] c.e.m.Foo - 通常の記録
                    2026-09-13 10:00:02 [ERROR] c.e.m.Foo - 失敗しました
                    2026-09-13 10:00:03 [DEBUG] c.e.m.Foo - 詳細な記録
                    """;
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), lines);

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<LogEntry> result = reader.readChannelLog("UCxxxxxxxx", 200, "ERROR").entries();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).message()).isEqualTo("失敗しました");
        }

        @Test
        @DisplayName("正常系：レベル指定は大文字小文字を区別しない")
        void testMethod07(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"),
                    "2026-09-13 10:00:00 [ERROR] c.e.m.Foo - 失敗しました\n");

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<LogEntry> result = reader.readChannelLog("UCxxxxxxxx", 200, "error").entries();

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系：レベルが空文字の場合は絞り込まない")
        void testMethod08(@TempDir Path tempDir) throws IOException {
            String lines = """
                    2026-09-13 10:00:01 [INFO] c.e.m.Foo - 通常の記録
                    2026-09-13 10:00:02 [ERROR] c.e.m.Foo - 失敗しました
                    """;
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), lines);

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<LogEntry> result = reader.readChannelLog("UCxxxxxxxx", 200, "").entries();

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("正常系：ログに実在するレベルだけが深刻な順で返る")
        void testMethod09(@TempDir Path tempDir) throws IOException {
            String lines = """
                    2026-09-13 10:00:01 [DEBUG] c.e.m.Foo - 詳細な記録
                    2026-09-13 10:00:02 [ERROR] c.e.m.Foo - 失敗しました
                    2026-09-13 10:00:03 [INFO] c.e.m.Foo - 通常の記録
                    2026-09-13 10:00:04 [INFO] c.e.m.Foo - 通常の記録2
                    """;
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), lines);

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<String> levels = reader.readChannelLog("UCxxxxxxxx", 200, null).availableLevels();

            // WARN や TRACE は出力されていないので選択肢に現れない
            assertThat(levels).containsExactly("ERROR", "INFO", "DEBUG");
        }

        @Test
        @DisplayName("正常系：レベルで絞り込んでも選択肢は絞り込み前の顔ぶれのまま返る")
        void testMethod10(@TempDir Path tempDir) throws IOException {
            String lines = """
                    2026-09-13 10:00:01 [INFO] c.e.m.Foo - 通常の記録
                    2026-09-13 10:00:02 [ERROR] c.e.m.Foo - 失敗しました
                    """;
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), lines);

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<String> levels = reader.readChannelLog("UCxxxxxxxx", 200, "ERROR").availableLevels();

            // ここが ERROR だけになると、画面から他のレベルへ戻れなくなる
            assertThat(levels).containsExactly("ERROR", "INFO");
        }

        @Test
        @DisplayName("正常系：件数制限は絞り込みの後に適用される")
        void testMethod11(@TempDir Path tempDir) throws IOException {
            String lines = """
                    2026-09-13 10:00:01 [ERROR] c.e.m.Foo - 古いエラー
                    2026-09-13 10:00:02 [INFO] c.e.m.Foo - 通常の記録1
                    2026-09-13 10:00:03 [INFO] c.e.m.Foo - 通常の記録2
                    2026-09-13 10:00:04 [ERROR] c.e.m.Foo - 新しいエラー
                    """;
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), lines);

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<LogEntry> result = reader.readChannelLog("UCxxxxxxxx", 2, "ERROR").entries();

            // 先に2件へ切り出してから絞り込むと「古いエラー」が落ちてしまう
            assertThat(result).hasSize(2);
            assertThat(result.get(0).message()).isEqualTo("古いエラー");
            assertThat(result.get(1).message()).isEqualTo("新しいエラー");
        }

        @Test
        @DisplayName("正常系：解析できない行しかない場合はレベルの選択肢が空になる")
        void testMethod12(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), "壊れた行\n続きの行\n");

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<String> levels = reader.readChannelLog("UCxxxxxxxx", 200, null).availableLevels();

            assertThat(levels).isEmpty();
        }

        @Test
        @DisplayName("正常系：未知のレベルは既知のレベルより後ろに並ぶ")
        void testMethod13(@TempDir Path tempDir) throws IOException {
            String lines = """
                    2026-09-13 10:00:01 [CUSTOM] c.e.m.Foo - 独自レベル
                    2026-09-13 10:00:02 [ERROR] c.e.m.Foo - 失敗しました
                    """;
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), lines);

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<String> levels = reader.readChannelLog("UCxxxxxxxx", 200, null).availableLevels();

            assertThat(levels).containsExactly("ERROR", "CUSTOM");
        }
    }

    @Nested
    @DisplayName("readSystemLog()")
    class ReadSystemLog {

        @Test
        @DisplayName("正常系：system.logの内容を読み取る")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("system.log"),
                    "2026-09-13 10:00:00 [INFO] c.e.m.App - 起動しました\n");

            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());
            List<LogEntry> result = reader.readSystemLog(200, null).entries();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).message()).isEqualTo("起動しました");
        }

        @Test
        @DisplayName("正常系：system.logが存在しない場合は空リストを返す")
        void testMethod02(@TempDir Path tempDir) {
            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());

            List<LogEntry> result = reader.readSystemLog(200, null).entries();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("deleteChannelLogs()")
    class DeleteChannelLogs {

        @Test
        @DisplayName("正常系：対象チャンネルの現行ログファイルを削除する")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), "");
            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());

            reader.deleteChannelLogs("UCxxxxxxxx");

            assertThat(Files.exists(tempDir.resolve("UCxxxxxxxx.log"))).isFalse();
        }

        @Test
        @DisplayName("正常系：ローテーション済みの日付付きファイルも一緒に削除する")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), "");
            Files.writeString(tempDir.resolve("UCxxxxxxxx.2026-09-12.log"), "");
            Files.writeString(tempDir.resolve("UCxxxxxxxx.2026-09-11.log"), "");
            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());

            reader.deleteChannelLogs("UCxxxxxxxx");

            assertThat(Files.exists(tempDir.resolve("UCxxxxxxxx.log"))).isFalse();
            assertThat(Files.exists(tempDir.resolve("UCxxxxxxxx.2026-09-12.log"))).isFalse();
            assertThat(Files.exists(tempDir.resolve("UCxxxxxxxx.2026-09-11.log"))).isFalse();
        }

        @Test
        @DisplayName("正常系：同じ長さで前方一致するだけの別チャンネルのログは削除しない")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UC123.log"), "");
            Files.writeString(tempDir.resolve("UC1234.log"), "");
            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());

            reader.deleteChannelLogs("UC123");

            assertThat(Files.exists(tempDir.resolve("UC123.log"))).isFalse();
            assertThat(Files.exists(tempDir.resolve("UC1234.log"))).isTrue();
        }

        @Test
        @DisplayName("正常系：system.logなど無関係なファイルは削除しない")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("UCxxxxxxxx.log"), "");
            Files.writeString(tempDir.resolve("system.log"), "");
            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());

            reader.deleteChannelLogs("UCxxxxxxxx");

            assertThat(Files.exists(tempDir.resolve("system.log"))).isTrue();
        }

        @Test
        @DisplayName("正常系：ログディレクトリが存在しない場合は何もせず正常終了する")
        void testMethod05(@TempDir Path tempDir) {
            ChannelLogReader reader = new ChannelLogReader(tempDir.resolve("not-exist").toString());

            assertThatCode(() -> reader.deleteChannelLogs("UCxxxxxxxx")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常系：対象ファイルが1つも無い場合も正常終了する")
        void testMethod06(@TempDir Path tempDir) {
            ChannelLogReader reader = new ChannelLogReader(tempDir.toString());

            assertThatCode(() -> reader.deleteChannelLogs("UCnotexist")).doesNotThrowAnyException();
        }
    }
}
