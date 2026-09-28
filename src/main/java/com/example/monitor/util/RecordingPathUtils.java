package com.example.monitor.util;
import java.nio.file.Path;

/**
 * 録画ファイルのパスから動画 ID を取り出す。
 *
 * <p>完成ファイルと断片を同じ動画にまとめ、履歴のある断片を誤削除しないための処理。
 */
public final class RecordingPathUtils {
    private RecordingPathUtils() {}
    /**
     * ファイル名の先頭にある動画 ID を返す。
     *
     * <p>yt-dlpの断片サフィックスも除き、出力名の先頭の動画IDを取り出す。
     * ファイル名の最初の {@code .} までを動画 ID とみなす。動画 ID に {@code .} を含むプラットフォームでは使えない。
     *
     * <p>録画関連のファイル（完成ファイル・結合前の断片・サムネイル）はどれも {@code <動画ID>.<拡張子>...}
     * （yt-dlp の出力テンプレート）で名付けるので、最初の {@code .} より前で同じ動画のファイルをまとめられる。
     * YouTube の動画 ID の文字集合に {@code .} は含まれないため、この切り出しは安全に成立する。
     *
     * @param file 録画ファイルのパス（出力名は {@code <動画ID>.<拡張子>} の形）
     * @return 動画 ID（ファイル名に {@code .} が無ければファイル名そのもの）
     */
    public static String videoId(Path file) {
        String name = file.getFileName().toString();
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
