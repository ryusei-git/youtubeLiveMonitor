package com.example.monitor.service;

import com.example.monitor.dto.LogEntry;
import com.example.monitor.dto.LogViewResponse;
import com.example.monitor.util.FileNameUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * チャンネルごとに分かれたログファイルを読み取り、画面で扱いやすい形に整形する。
 *
 * <p>ログの振り分け自体は Logback の {@code SiftingAppender} が行っており、
 * 監視ループが MDC に設定したチャンネル ID をキーに
 * {@code logs/channels/{チャンネルID}.log} へ自動的に書き分けられている。
 * このクラスはそうして出来上がったファイルを読むだけで、書き込みには関与しない。
 *
 * <p>解析に使うパターンは {@code logback-spring.xml} の出力形式
 * （{@code %d{yyyy-MM-dd HH:mm:ss} [%level] %logger{36} - %msg%n}）と対応している。
 * <b>片方だけを変更すると解析できなくなる</b>ので、変更時は必ず両方を合わせること。
 */
@Service
@Slf4j
public class ChannelLogReader {

    /** チャンネルに紐づかないログ（監視ループ全体の動作など）が書き込まれるファイル名。 */
    private static final String SYSTEM_LOG_FILE_NAME = "system";

    /** ログファイルの拡張子。 */
    private static final String LOG_FILE_EXTENSION = ".log";

    /** logback-spring.xml の出力形式に対応した、1 行を 4 項目に分解する正規表現。 */
    private static final Pattern LOG_LINE_PATTERN =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) \\[(\\w+)] (\\S+) - (.*)$");

    /**
     * ログレベルを深刻な順に並べるための基準。
     *
     * <p>画面の絞り込みでは「まず ERROR を見たい」場面がほとんどなので、
     * 実在するレベルをこの順に並べ替えて返す（アルファベット順だと DEBUG が先頭に来てしまう）。
     * ここに無いレベルは末尾へ回す。
     */
    private static final List<String> LEVEL_SEVERITY_ORDER =
            List.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    /**
     * チャンネル別ログの出力先ディレクトリ。
     *
     * <p>定数ではなくコンストラクタ注入にしているのはテスト容易性のため。
     * 固定パスのままだとテストのたびに実際のプロジェクト直下の {@code logs/} を
     * 読み書きすることになり、他のテストや実運用のログと干渉してしまう。
     */
    private final Path channelLogDirectory;

    /**
     * @param channelLogDirectory チャンネル別ログの出力先ディレクトリ
     *                            （{@code logback-spring.xml} の出力先と一致させること）
     */
    public ChannelLogReader(@Value("${monitor.logs.directory:logs/channels}") String channelLogDirectory) {
        this.channelLogDirectory = Path.of(channelLogDirectory);
    }

    /**
     * ログファイルが存在するチャンネル ID の一覧を返す。画面のチャンネル選択に使う。
     *
     * <p>日付付きのローテーション済みファイルとシステムログは除外する。
     *
     * @return チャンネル ID の一覧（昇順）。ログディレクトリがなければ空リスト
     */
    public List<String> listChannelsWithLogs() {
        if (!Files.isDirectory(channelLogDirectory)) {
            return List.of();
        }
        try (Stream<Path> logFiles = Files.list(channelLogDirectory)) {
            return logFiles
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(LOG_FILE_EXTENSION))
                    .map(fileName -> FileNameUtils.stripExtension(fileName, LOG_FILE_EXTENSION))
                    // ローテーション済みファイルは "UCxxx.2026-09-13" のように名前に日付が入る
                    .filter(channelId -> !channelId.contains("."))
                    .filter(channelId -> !channelId.equals(SYSTEM_LOG_FILE_NAME))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("チャンネル別ログの一覧取得に失敗しました", e);
            return List.of();
        }
    }

    /**
     * 特定チャンネルのログを新しい方から指定件数だけ読み取る。
     *
     * @param youtubeChannelId 対象のチャンネル ID
     * @param limit            取得する最大件数
     * @param level            このレベルの行だけに絞り込む。{@code null} または空なら絞り込まない
     * @return 解析済みのログと、そのファイルに実在するレベルの一覧。ファイルがなければ空
     */
    public LogViewResponse readChannelLog(String youtubeChannelId, int limit, String level) {
        return readLogFile(channelLogDirectory.resolve(youtubeChannelId + LOG_FILE_EXTENSION), limit, level);
    }

    /**
     * チャンネルに紐づかないシステムログを新しい方から指定件数だけ読み取る。
     *
     * @param limit 取得する最大件数
     * @param level このレベルの行だけに絞り込む。{@code null} または空なら絞り込まない
     * @return 解析済みのログと、そのファイルに実在するレベルの一覧。ファイルがなければ空
     */
    public LogViewResponse readSystemLog(int limit, String level) {
        return readLogFile(channelLogDirectory.resolve(SYSTEM_LOG_FILE_NAME + LOG_FILE_EXTENSION), limit, level);
    }

    /**
     * 指定チャンネルのログファイルを、ローテーション済みのものも含めて全て削除する。
     *
     * <p>チャンネルを監視対象から削除した際に呼ぶ想定。通知履歴が DB の連鎖削除で
     * 一緒に消えるのに対し、ログファイルだけがディスクに残り続けるのは片手落ちなため。
     * 削除に失敗しても例外は投げない。ログの掃除に失敗したからといって
     * チャンネル削除そのものを失敗扱いにする必要はないため。
     *
     * @param youtubeChannelId 削除対象のチャンネル ID
     */
    public void deleteChannelLogs(String youtubeChannelId) {
        if (!Files.isDirectory(channelLogDirectory)) {
            return;
        }

        String exactFileName = youtubeChannelId + LOG_FILE_EXTENSION;
        // ローテーション済みファイルは "UCxxx.2026-09-13.log" のようにチャンネルIDの直後に
        // ドット区切りで日付が続く。ドットまで含めて前方一致させないと、
        // 同じ長さの別チャンネルIDが偶然前方一致してしまう事故を防げない。
        String rotatedFilePrefix = youtubeChannelId + ".";

        try (Stream<Path> logFiles = Files.list(channelLogDirectory)) {
            List<Path> targets = logFiles
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.equals(exactFileName) || fileName.startsWith(rotatedFilePrefix);
                    })
                    .toList();

            for (Path target : targets) {
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            log.error("チャンネル別ログの削除に失敗しました: channel={}", youtubeChannelId, e);
        }
    }

    /**
     * ログファイルを読み込んで解析し、絞り込んだうえで末尾から指定件数を返す。
     *
     * <p>件数の切り出しは<b>絞り込みの後</b>に行う。先に切り出してから絞り込むと、
     * 「ERROR を 200 件見たい」という指定に対して「直近 200 行のうちの ERROR」しか返らず、
     * 件数を大きくしない限り古いエラーにたどり着けなくなるため。
     *
     * @param logFilePath 読み込むファイル
     * @param limit       返す最大件数
     * @param level       絞り込むレベル。{@code null} または空なら絞り込まない
     * @return 解析済みのログと実在するレベルの一覧。読めない場合は空
     */
    private LogViewResponse readLogFile(Path logFilePath, int limit, String level) {
        if (!Files.isRegularFile(logFilePath)) {
            return new LogViewResponse(List.of(), List.of());
        }

        List<String> rawLines;
        try {
            rawLines = Files.readAllLines(logFilePath);
        } catch (IOException e) {
            log.error("ログファイルの読み込みに失敗しました: {}", logFilePath, e);
            return new LogViewResponse(List.of(), List.of());
        }

        List<LogEntry> allEntries = parseLogLines(rawLines);
        // 選択肢は必ず絞り込み前の全行から作る（理由は LogViewResponse の JavaDoc 参照）
        List<String> availableLevels = collectLevels(allEntries);

        List<LogEntry> filtered = filterByLevel(allEntries, level);
        int fromIndex = Math.max(0, filtered.size() - limit);
        return new LogViewResponse(availableLevels, filtered.subList(fromIndex, filtered.size()));
    }

    /**
     * ログに実在するレベルを、深刻な順に重複なく集める。
     *
     * @param entries 絞り込み前の全ログ
     * @return 実在するレベルの一覧。解析できずレベルが付かない行しかなければ空リスト
     */
    private List<String> collectLevels(List<LogEntry> entries) {
        Set<String> present = entries.stream()
                .map(LogEntry::level)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return present.stream()
                .sorted(Comparator.comparingInt(levelName -> {
                    int index = LEVEL_SEVERITY_ORDER.indexOf(levelName);
                    // 未知のレベルは末尾へ回す
                    return index < 0 ? LEVEL_SEVERITY_ORDER.size() : index;
                }))
                .toList();
    }

    /**
     * 指定したレベルの行だけを残す。
     *
     * @param entries 絞り込み前の全ログ
     * @param level   絞り込むレベル。{@code null} または空なら絞り込まない
     * @return 絞り込み後のログ
     */
    private List<LogEntry> filterByLevel(List<LogEntry> entries, String level) {
        if (level == null || level.isBlank()) {
            return entries;
        }
        return entries.stream()
                .filter(entry -> level.equalsIgnoreCase(entry.level()))
                .toList();
    }

    /**
     * ログの各行を解析して {@link LogEntry} に変換する。
     *
     * <p>パターンに当てはまらない行はスタックトレースの続きとみなし、
     * 直前のエントリのメッセージへ改行付きで連結する。
     * こうしないと例外 1 件がバラバラの行として並び、画面で読みづらくなる。
     *
     * @param rawLines ログファイルの全行
     * @return 解析済みのログ
     */
    private List<LogEntry> parseLogLines(List<String> rawLines) {
        List<LogEntry> entries = new ArrayList<>();

        for (String rawLine : rawLines) {
            Matcher matcher = LOG_LINE_PATTERN.matcher(rawLine);

            if (matcher.matches()) {
                entries.add(new LogEntry(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
            } else if (entries.isEmpty()) {
                // ファイル先頭がいきなり解析できない行だった場合。連結先がないので本文だけの行として扱う
                entries.add(new LogEntry(null, null, null, rawLine));
            } else {
                entries.set(entries.size() - 1, appendToMessage(entries.get(entries.size() - 1), rawLine));
            }
        }

        return entries;
    }

    /**
     * 既存のログエントリのメッセージに続きの行を連結した、新しいエントリを返す。
     *
     * @param entry           連結先のエントリ
     * @param continuationLine 連結する行
     * @return メッセージを連結した新しいエントリ
     */
    private LogEntry appendToMessage(LogEntry entry, String continuationLine) {
        return new LogEntry(
                entry.timestamp(),
                entry.level(),
                entry.loggerName(),
                entry.message() + "\n" + continuationLine);
    }

}
