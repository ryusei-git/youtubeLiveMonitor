package com.example.monitor.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 録画がまだ進んでいるかを、出力の最終更新時刻から読み取る。
 *
 * <p><b>録画ファイルと yt-dlp のログの両方を見る。</b>どちらか一方では足りない。
 * YouTube の {@code --live-from-start} は {@code --no-progress} のため、ログが開始直後から配信終了まで
 * 1 行も書かれない（本番で 78 分更新なしを確認した）。ログだけで判定すると正常な録画を止めてしまう。
 * 一方、起動直後の抽出中は録画ファイルがまだ無い。
 *
 * <p>{@link Path} と動画 ID だけを受け取るのは、{@code Process} を持たない呼び出し元
 * （再起動後に残った yt-dlp を見張る場合）でもそのまま使えるようにするため。
 */
public final class RecordingActivity {

    private RecordingActivity() {
    }

    /**
     * 動画 1 本ぶんの出力のうち、最も新しい更新時刻を返す。
     *
     * <p>録画フォルダの {@code {動画ID}.*}（{@code .f137.mp4}・{@code -FragN}・{@code .ytdl}・
     * {@code .temp.mp4}・{@code .mp4}）と {@link YtDlpLogFile#of(String)} を見る。
     * 走査の途中で消えたファイルは飛ばす（yt-dlp は結合のたびに断片を消すため）。
     *
     * @param outputDirectory 録画フォルダ
     * @param videoId         対象の動画 ID
     * @return 最も新しい更新時刻。対象が 1 つも無ければ {@link Instant#EPOCH}
     * @throws IOException 録画フォルダの一覧を読めなかった場合（呼び出し側は「判定できなかった」として扱う）
     */
    public static Instant lastModified(Path outputDirectory, String videoId) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(outputDirectory)) {
            list.filter(Files::isRegularFile)
                    .filter(file -> RecordingPathUtils.videoId(file).equals(videoId))
                    .forEach(files::add);
        }
        files.add(YtDlpLogFile.of(videoId));

        Instant latest = Instant.EPOCH;
        for (Path file : files) {
            try {
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (modified.isAfter(latest)) {
                    latest = modified;
                }
            } catch (NoSuchFileException e) {
                // 断片が途中で消えた、またはログがまだ無い
            }
        }
        return latest;
    }
}
