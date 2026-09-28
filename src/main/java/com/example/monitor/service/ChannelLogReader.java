package com.example.monitor.service;

import com.example.monitor.dto.LogEntry;
import com.example.monitor.dto.LogViewResponse;
import com.example.monitor.util.FileNameUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * チャンネルごとに分かれたログファイルを読み取り、画面で扱いやすい形に整形する。
 *
 * <p>ログの振り分け自体は Logback の {@code SiftingAppender} が行っており、
 * 監視ループが MDC に設定したチャンネル ID をキーに
 * {@code logs/channels/{チャンネルID}.log} へ自動的に書き分けられている。
 * このクラスはそうして出来上がったファイルを読むだけで、書き込みには関与しない。
 *
 * <p>解析に使うパターンは {@code logback-spring.xml} の出力形式の先頭
 * （{@code %d{yyyy-MM-dd HH:mm:ss} [%level] %logger{36} - }）と対応していて、その後ろはすべて本文として扱う。
 * <b>片方だけを変更すると解析できなくなる</b>ので、変更時は必ず両方を合わせること。
 *
 * <p>リクエストの処理中に出た行は、本文の先頭に {@code [相関ID] } が付く（{@code logback-spring.xml} の
 * {@code %replace(%X{requestId}){'^(.+)$', '[$1] '}}）。パターンは本文の中身を問わないので、
 * これも本文（{@code LogEntry.message}）の一部としてそのまま返す。相関 ID を別の項目に切り出していないのは、
 * 相関 ID を出す前に書かれた行（ローテーション済みのファイルに 14 日分残る）と同じ正規表現で読めるようにするため。
 */
@Service
@Slf4j
public class ChannelLogReader {

    /** チャンネルに紐づかないログ（監視ループ全体の動作など）が書き込まれるファイル名。 */
    private static final String SYSTEM_LOG_FILE_NAME = "system";

    /** ログファイルの拡張子。 */
    private static final String LOG_FILE_EXTENSION = ".log";

    /**
     * logback-spring.xml の出力形式に対応した、1 行を 4 項目に分解する正規表現。
     * 相関 ID（{@code [相関ID] }）は 4 項目目の本文に含まれる。
     */
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
     * ログファイルを 1 行ずつ解析し、絞り込んだうえで末尾から指定件数を返す。
     *
     * <p>件数の切り出しは<b>絞り込みの後</b>に行う。先に切り出してから絞り込むと、
     * 「ERROR を 200 件見たい」という指定に対して「直近 200 行のうちの ERROR」しか返らず、
     * 件数を大きくしない限り古いエラーにたどり着けなくなるため。
     *
     * <p><b>ファイルを丸ごと読まず、手元には返す分（絞り込みに合う末尾の {@code limit} 件）だけを持つ。</b>
     * 以前は全行を読み込んで全行を {@link LogEntry} にし、スタックトレースの続き行も 1 行ごとに
     * エントリを作り直していた（k 行のトレースで k²/2 行分の複製）。例外の多いシステムログ
     * （3.8MB・約 4 万行）を 1 回開くだけで約 0.5GB を確保して捨てていた（#184）。
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

        // 選択肢は必ず絞り込み前の全行から作る（理由は LogViewResponse の JavaDoc 参照）
        Set<String> presentLevels = new LinkedHashSet<>();
        Deque<LogEntry> latestEntries = new ArrayDeque<>();
        PendingEntry pending = null;

        try (BufferedReader reader = Files.newBufferedReader(logFilePath)) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                Matcher matcher = LOG_LINE_PATTERN.matcher(rawLine);

                if (matcher.matches()) {
                    keepIfMatched(latestEntries, pending, level, limit);
                    presentLevels.add(matcher.group(2));
                    pending = new PendingEntry(matcher.group(1), matcher.group(2), matcher.group(3),
                            new StringBuilder(matcher.group(4)));
                } else if (pending == null) {
                    // ファイル先頭がいきなり解析できない行だった場合。連結先がないので本文だけの行として扱う
                    pending = new PendingEntry(null, null, null, new StringBuilder(rawLine));
                } else {
                    // パターンに当てはまらない行はスタックトレースの続きとみなし、組み立て中の 1 件へ連結する。
                    // こうしないと例外 1 件がバラバラの行として並び、画面で読みづらくなる
                    pending.message().append('\n').append(rawLine);
                }
            }
        } catch (IOException e) {
            log.error("ログファイルの読み込みに失敗しました: {}", logFilePath, e);
            return new LogViewResponse(List.of(), List.of());
        }
        keepIfMatched(latestEntries, pending, level, limit);

        return new LogViewResponse(sortBySeverity(presentLevels), List.copyOf(latestEntries));
    }

    /**
     * 組み上がった 1 件が絞り込みに合えば残し、{@code limit} 件を超えた分は古い方から捨てる。
     *
     * <p>返すのは末尾の {@code limit} 件だけなので、それより古いものを持ち続ける理由がない。
     * 全件を溜めてから切り出すと、手元に持つ量がファイルの大きさに比例してしまう。
     *
     * @param latestEntries 絞り込みに合った直近のログ（古い順）
     * @param pending       組み上がった 1 件。まだ 1 行も読んでいなければ {@code null}
     * @param level         絞り込むレベル。{@code null} または空なら絞り込まない
     * @param limit         残す最大件数
     */
    private static void keepIfMatched(Deque<LogEntry> latestEntries, PendingEntry pending, String level, int limit) {
        if (pending == null) {
            return;
        }
        if (level != null && !level.isBlank() && !level.equalsIgnoreCase(pending.level())) {
            return;
        }
        latestEntries.addLast(pending.toLogEntry());
        if (latestEntries.size() > limit) {
            latestEntries.removeFirst();
        }
    }

    /**
     * ログに実在するレベルを深刻な順に並べる。
     *
     * @param presentLevels 絞り込み前の全行から集めたレベル（最初に現れた順）
     * @return 実在するレベルの一覧。解析できずレベルが付かない行しかなければ空リスト
     */
    private static List<String> sortBySeverity(Set<String> presentLevels) {
        return presentLevels.stream()
                .sorted(Comparator.comparingInt(levelName -> {
                    int index = LEVEL_SEVERITY_ORDER.indexOf(levelName);
                    // 未知のレベルは末尾へ回す
                    return index < 0 ? LEVEL_SEVERITY_ORDER.size() : index;
                }))
                .toList();
    }

    /**
     * 続き行を足している途中の 1 件。
     *
     * <p>{@link LogEntry} は後から本文を変えられないため、続き行のたびに作り直すと
     * 本文全体を毎回複製することになる。本文だけを {@link StringBuilder} に溜め、
     * 組み上がったときに 1 回だけ {@link LogEntry} にする。
     *
     * @param timestamp  出力時刻。ファイル先頭の解析できない行では {@code null}
     * @param level      ログレベル。ファイル先頭の解析できない行では {@code null}
     * @param loggerName 出力元クラス名。ファイル先頭の解析できない行では {@code null}
     * @param message    本文（続き行を改行付きで足していく）
     */
    private record PendingEntry(String timestamp, String level, String loggerName, StringBuilder message) {

        LogEntry toLogEntry() {
            return new LogEntry(timestamp, level, loggerName, message.toString());
        }
    }
}
