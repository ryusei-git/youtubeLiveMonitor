package com.example.monitor.util;
import java.nio.file.Path;

/** 完成ファイルと断片を同じ動画にまとめ、履歴のある断片を誤削除しないための処理。 */
public final class RecordingPathUtils {
    private RecordingPathUtils() {}
    /** yt-dlpの断片サフィックスも除き、出力名の先頭の動画IDを取り出す。 */
    public static String videoId(Path file) {
        String name = file.getFileName().toString();
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
