package com.example.monitor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.example.monitor.dto.LogEntry;
import com.example.monitor.dto.LogViewResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.StringUtils;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code logback-spring.xml} の 3 つの {@code <pattern>} で実際に整形した行を、
 * {@link ChannelLogReader#readChannelLog} で読み返して確かめる。
 *
 * <p>書式とパーサー（{@code ChannelLogReader.LOG_LINE_PATTERN}）は対になっていて、片方だけ変えると
 * ログ画面が壊れる（docs/pitfalls.md「ログ書式を変えるならパーサーも直す」）。{@code logback-spring.xml} は
 * {@code NopStatusListener} で logback 自身の警告を捨てているので、書式を書き間違えても起動時に何も言われない。
 * 手書きのログ行で解析を確かめる {@code ChannelLogReaderTest} では、この食い違いに気づけない。
 *
 * <p>{@code CHANNEL_LOG} の {@code <fileNamePattern>} も、{@code ChannelLogReader} が日付で回った過去のファイルを
 * 探す名前の形と対になっている（#558）。片方だけ変えると、ログ画面から前日より前の行が黙って消え、何も失敗しないので
 * 気づけない。そのため {@code <fileNamePattern>} から作った名前のファイルも読み返す（{@code ReadBack} の testMethod04）。
 *
 * <p>{@code logback-spring.xml} を読み込んだ {@code LoggerContext} は作らない。作るとチャンネル別の
 * ファイル出力が作業ディレクトリの {@code logs/} に書き込まれる（#187）。{@code <pattern>} の文字列だけを取り出し、
 * このテストの中だけの {@link PatternLayout} で 1 件ずつ整形する。
 */
@DisplayName("logback-spring.xml の書式の決まり")
class LogbackPatternConventionTest {

    /** 出力元のロガー名。36 文字未満なので {@code %logger{36}} で短縮されず、そのまま出る。 */
    private static final String LOGGER_NAME = "com.example.monitor.service.Foo";

    /** 整形する 1 件の時刻。{@code %d} は JVM の既定のタイムゾーンで出す。 */
    private static final Instant LOGGED_AT = Instant.parse("2026-09-27T03:04:05Z");

    private static final String REQUEST_ID = "3f2a9c1e-0b7d-4c55-9a2e-1d6f0e8b4a21";

    @Nested
    @DisplayName("<pattern> の読み取り")
    class Patterns {

        @Test
        @DisplayName("正常系：CONSOLE・CHANNEL_LOG・SERVICE_FILE の 3 つの <pattern> を読み取れる")
        void testMethod01() throws Exception {
            List<String> patterns = logbackPatterns();

            assertThat(patterns).hasSize(3);
            assertThat(patterns).allSatisfy(pattern -> assertThat(pattern).isNotBlank());
        }
    }

    @Nested
    @DisplayName("ChannelLogReader.readChannelLog() で読み返す")
    class ReadBack {

        @Test
        @DisplayName("正常系：どの <pattern> で出した行も、時刻・レベル・出力元・本文に分解できる")
        void testMethod01(@TempDir Path tempDir) throws Exception {
            for (String pattern : logbackPatterns()) {
                String text = format(pattern, Level.INFO, "配信を検知しました: channel=UCxxxxxxxx", null, Map.of());

                List<LogEntry> entries = readBack(tempDir, text);

                assertThat(entries).as("<pattern> %s", pattern).hasSize(1);
                LogEntry entry = entries.get(0);
                assertThat(entry.timestamp()).as("<pattern> %s", pattern).isEqualTo(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(LOGGED_AT));
                assertThat(entry.level()).as("<pattern> %s", pattern).isEqualTo("INFO");
                assertThat(entry.loggerName()).as("<pattern> %s", pattern).isEqualTo(LOGGER_NAME);
                assertThat(entry.message()).as("<pattern> %s", pattern).isEqualTo("配信を検知しました: channel=UCxxxxxxxx");
            }
        }

        @Test
        @DisplayName("正常系：例外のスタックは続きの行として 1 件の本文にまとまる")
        void testMethod02(@TempDir Path tempDir) throws Exception {
            for (String pattern : logbackPatterns()) {
                String text = format(pattern, Level.ERROR, "録画に失敗しました",
                        new IllegalStateException("yt-dlp が異常終了しました"), Map.of());

                List<LogEntry> entries = readBack(tempDir, text);

                assertThat(entries).as("<pattern> %s", pattern).hasSize(1);
                assertThat(entries.get(0).level()).as("<pattern> %s", pattern).isEqualTo("ERROR");
                assertThat(entries.get(0).message()).as("<pattern> %s", pattern)
                        .startsWith("録画に失敗しました\n")
                        .contains("java.lang.IllegalStateException: yt-dlp が異常終了しました")
                        .contains("\tat ");
            }
        }

        @Test
        @DisplayName("正常系：相関 ID がある行は本文の先頭に [相関ID] が付き、無い行には付かない")
        void testMethod03(@TempDir Path tempDir) throws Exception {
            for (String pattern : logbackPatterns()) {
                String text = format(pattern, Level.WARN, "リクエストを処理できませんでした: status=400", null,
                        Map.of("requestId", REQUEST_ID))
                        + format(pattern, Level.INFO, "巡回を始めます", null, Map.of());

                List<LogEntry> entries = readBack(tempDir, text);

                assertThat(entries).as("<pattern> %s", pattern).hasSize(2);
                assertThat(entries.get(0).message()).as("<pattern> %s", pattern)
                        .isEqualTo("[" + REQUEST_ID + "] リクエストを処理できませんでした: status=400");
                assertThat(entries.get(1).message()).as("<pattern> %s", pattern).isEqualTo("巡回を始めます");
            }
        }

        @Test
        @DisplayName("正常系：CHANNEL_LOG の fileNamePattern の名前で日付が回ったファイルも、現行のファイルの前に続けて読む")
        void testMethod04(@TempDir Path tempDir) throws Exception {
            String rotatedFileName = Path.of(channelLogFileNamePattern()
                    .replace("${channelId}", "UCconvention")
                    .replace("%d{yyyy-MM-dd}", "2026-09-26")).getFileName().toString();
            // 日付の書き方を変えると置き換えが外れて % が残る。
            // そのときは ChannelLogReader が探す名前の形も直すこと
            assertThat(rotatedFileName).isEqualTo("UCconvention.2026-09-26.log");

            for (String pattern : logbackPatterns()) {
                Files.writeString(tempDir.resolve(rotatedFileName),
                        format(pattern, Level.ERROR, "前日の録画に失敗しました", null, Map.of()));
                Files.writeString(tempDir.resolve("UCconvention.log"),
                        format(pattern, Level.INFO, "巡回を始めます", null, Map.of()));

                LogViewResponse response = new ChannelLogReader(tempDir.toString())
                        .readChannelLog("UCconvention", 200, null);

                assertThat(response.entries()).as("<pattern> %s", pattern)
                        .extracting(LogEntry::message)
                        .containsExactly("前日の録画に失敗しました", "巡回を始めます");
                assertThat(response.availableLevels()).as("<pattern> %s", pattern).containsExactly("ERROR", "INFO");
            }
        }
    }

    @Nested
    @DisplayName("秘密の伏せ字")
    class Redaction {

        @Test
        @DisplayName("正常系：本文の Webhook のトークンと、例外の本文の API キーを *** に伏せる")
        void testMethod01(@TempDir Path tempDir) throws Exception {
            for (String pattern : logbackPatterns()) {
                String text = format(pattern, Level.ERROR,
                        "送信に失敗しました https://discord.com/api/webhooks/123456789/T13SECRETTOKEN?wait=true",
                        googleApiException(), Map.of());

                String message = readBack(tempDir, text).get(0).message();

                assertThat(message).as("<pattern> %s", pattern)
                        .doesNotContain("T13SECRET")
                        .contains("https://discord.com/api/webhooks/123456789/***?wait=true")
                        .contains("search?key=***&q=x")
                        .contains("&key=***&part=snippet");
            }
        }

        @Test
        @DisplayName("正常系：例外のスタックは 1 回だけ出る")
        void testMethod02(@TempDir Path tempDir) throws Exception {
            for (String pattern : logbackPatterns()) {
                String text = format(pattern, Level.ERROR, "検索に失敗しました", googleApiException(), Map.of());

                String message = readBack(tempDir, text).get(0).message();

                assertThat(StringUtils.countOccurrencesOf(message, "java.lang.RuntimeException: wrap")).as("<pattern> %s", pattern).isEqualTo(1);
                assertThat(StringUtils.countOccurrencesOf(message, "Caused by: java.io.IOException")).as("<pattern> %s", pattern).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("正常系：URL の引数でない key= は伏せない")
        void testMethod03(@TempDir Path tempDir) throws Exception {
            for (String pattern : logbackPatterns()) {
                String text = format(pattern, Level.INFO, "ffprobe -of default=noprint_wrappers=1:nokey=1", null, Map.of());

                assertThat(readBack(tempDir, text).get(0).message()).as("<pattern> %s", pattern)
                        .isEqualTo("ffprobe -of default=noprint_wrappers=1:nokey=1");
            }
        }
    }

    /** クラスパスの {@code logback-spring.xml} から {@code <pattern>} の中身を出てくる順に読む。 */
    private static List<String> logbackPatterns() throws Exception {
        try (InputStream in = LogbackPatternConventionTest.class.getResourceAsStream("/logback-spring.xml")) {
            assertThat(in).as("クラスパスに logback-spring.xml がある").isNotNull();
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in)
                    .getElementsByTagName("pattern");
            List<String> patterns = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                patterns.add(nodes.item(i).getTextContent().trim());
            }
            return patterns;
        }
    }

    /**
     * クラスパスの {@code logback-spring.xml} から、チャンネル別ログ（{@code CHANNEL_LOG}）の
     * {@code <fileNamePattern>} を読む。{@code ${channelId}} を含むものがそれに当たる。
     */
    private static String channelLogFileNamePattern() throws Exception {
        try (InputStream in = LogbackPatternConventionTest.class.getResourceAsStream("/logback-spring.xml")) {
            assertThat(in).as("クラスパスに logback-spring.xml がある").isNotNull();
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in)
                    .getElementsByTagName("fileNamePattern");
            for (int i = 0; i < nodes.getLength(); i++) {
                String fileNamePattern = nodes.item(i).getTextContent().trim();
                if (fileNamePattern.contains("${channelId}")) {
                    return fileNamePattern;
                }
            }
        }
        throw new AssertionError("logback-spring.xml に ${channelId} を含む <fileNamePattern> が無い");
    }

    /**
     * 本番と同じ {@code <pattern>} で 1 件を整形する。
     *
     * <p>MDC は必ず {@code mdc} で渡す。渡さないと、このテストの中だけの {@link LoggerContext} から MDC を
     * 読もうとして失敗する。
     */
    private static String format(String pattern, Level level, String message, Throwable throwable,
                                 Map<String, String> mdc) {
        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(pattern);
        layout.start();

        LoggingEvent event = new LoggingEvent(LogbackPatternConventionTest.class.getName(),
                context.getLogger(LOGGER_NAME), level, message, throwable, null);
        event.setInstant(LOGGED_AT);
        event.setMDCPropertyMap(mdc);
        return layout.doLayout(event);
    }

    /** 整形した行をチャンネル別ログのファイルに書き、{@link ChannelLogReader} で読み返す。 */
    private static List<LogEntry> readBack(Path directory, String text) throws IOException {
        Files.writeString(directory.resolve("UCconvention.log"), text);
        return new ChannelLogReader(directory.toString()).readChannelLog("UCconvention", 200, null).entries();
    }

    /** Google の API ライブラリの例外のように、本文にキー付きの URL を持つ例外。 */
    private static RuntimeException googleApiException() {
        return new RuntimeException("wrap https://www.googleapis.com/youtube/v3/search?key=T13SECRETKEY&q=x",
                new IOException("403 Forbidden\nGET https://www.googleapis.com/youtube/v3/videos?id=abc&key=T13SECRETKEY2&part=snippet"));
    }
}
