package com.example.monitor.util;

import java.nio.file.Path;

/**
 * 録画と手動ダウンロードの yt-dlp の出力先。
 *
 * <p>JVM が止まっても yt-dlp が止まらないよう、出力はパイプではなくここへ向ける
 * （{@code docs/pitfalls.md}「外部プロセスの出力を JVM へのパイプにすると、再起動で yt-dlp が止まる」参照）。
 * 録画（{@code StreamRecorder}）と手動ダウンロード（{@code VideoDownloadService}）の 2 か所で使うため、
 * 場所の決まりをここ 1 つにまとめている。
 *
 * <p>ほかのログ（{@code logback-spring.xml} の {@code logs/channels/}）と同じ {@code logs/} に置く。
 * 録画フォルダの下に置くと、録画ファイルの走査・削除・孤立ファイルの判定に混ざるため。
 * 動画 ID ごとのファイルにしているのは、録り直しも同じファイルに追記され、1 本の経緯を 1 か所で追えるようにするため。
 */
public final class YtDlpLogFile {

    private YtDlpLogFile() {
    }

    /**
     * 動画 1 本ぶんの yt-dlp の出力を書き込むファイルを返す。
     *
     * @param videoId 対象の動画 ID
     * @return {@code logs/yt-dlp/<動画ID>.log}（作業ディレクトリからの相対パス）
     */
    public static Path of(String videoId) {
        return Path.of("logs", "yt-dlp", videoId + ".log");
    }
}
