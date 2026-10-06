package com.example.monitor.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * 同じ理由で、録画から MP3 を作る ffmpeg（{@code RecordingAudioExtractor}）の出力もここへ追記する。
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

    /**
     * 動画 1 本ぶんの yt-dlp の出力の末尾を返す。
     *
     * <p>録画の失敗を管理者へ知らせるとき（{@code StreamRecorder}）に、失敗の理由（多くは最後の
     * {@code ERROR:} 行）を Discord の通知だけで読めるようにするために使う。
     * 先頭から読まないのは、録り直しも同じファイルに追記され、長くなりうるため。
     *
     * <p>読めなかったときは空文字を返す。通知に添えるだけのものなので、読めないことで通知そのものを止めないため。
     *
     * @param videoId  対象の動画 ID
     * @param maxBytes 読む末尾のバイト数の上限
     * @return 末尾の数行（途中から読んだ最初の行は捨て、前後の空白を除く）。ファイルが無い・読めないときは空文字
     */
    public static String tail(String videoId, int maxBytes) {
        try (SeekableByteChannel channel = Files.newByteChannel(of(videoId))) {
            long size = channel.size();
            long start = Math.max(0, size - maxBytes);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // 1 回の read で全部返るとは限らないので、読み切るまで繰り返す
            }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            if (start > 0) {
                // 途中から読んだ最初の行は欠けている（UTF-8 の文字の途中から始まることもある）ので捨てる
                int firstNewline = text.indexOf('\n');
                text = firstNewline < 0 ? "" : text.substring(firstNewline + 1);
            }
            return text.strip();
        } catch (IOException e) {
            return "";
        }
    }
}
