package com.example.monitor.util;

/**
 * ファイル名の文字列操作をまとめた共通処理。
 *
 * <p>特定の機能に紐づかない汎用的な処理のため、状態を持たない独立クラスとして切り出している。
 * 複数のクラスから同じ処理が必要になったときに、それぞれが個別に実装して
 * 微妙に異なる挙動になってしまうのを避けるのが目的。
 */
public final class FileNameUtils {

    private FileNameUtils() {
    }

    /**
     * ファイル名の末尾から指定した拡張子を取り除く。
     *
     * <p>指定した拡張子で終わっていないファイル名を渡した場合は、そのまま変更せずに返す。
     *
     * @param fileName  対象のファイル名
     * @param extension 取り除きたい拡張子（{@code ".log"} のようにドットを含める）
     * @return 拡張子を除いたファイル名。末尾が一致しなければ元のファイル名
     */
    public static String stripExtension(String fileName, String extension) {
        if (!fileName.endsWith(extension)) {
            return fileName;
        }
        return fileName.substring(0, fileName.length() - extension.length());
    }
}
