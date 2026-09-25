package com.example.monitor.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.io.IOException;

/**
 * 録画の保存先があるボリュームの容量を調べる。
 *
 * <p>保存先が未作成でも、その親が属するボリュームの空き容量を確認するための処理。
 */
public final class DiskSpaceUtils {
    private DiskSpaceUtils() {}
    /**
     * 指定したディレクトリがあるボリュームの総容量と空き容量を返す。
     *
     * <p>取得失敗をゼロ容量と誤認させないため、値と失敗理由を分ける。
     * ディレクトリが無ければ、存在する親までさかのぼって調べる。
     *
     * @param directory 調べるディレクトリ（未作成でもよい）
     * @return 容量。取得できなければ容量は {@code null} で、{@code error} に理由が入る（例外は投げない）
     */
    public static Capacity read(Path directory) {
        Path path = directory.toAbsolutePath();
        while (path != null && !Files.exists(path)) path = path.getParent();
        try {
            if (path == null) throw new IOException("保存先の親を確認できません");
            var store = Files.getFileStore(path);
            return new Capacity(store.getTotalSpace(), store.getUsableSpace(), null, LocalDateTime.now());
        } catch (IOException | SecurityException e) {
            return new Capacity(null, null, "保存先ボリュームの容量を取得できません", LocalDateTime.now());
        }
    }
    /**
     * ボリュームの容量。
     *
     * <p>録画ファイルの合計とは別の指標として表示する。
     *
     * @param totalBytes 総容量（取得できなければ {@code null}）
     * @param usableBytes 空き容量（取得できなければ {@code null}）
     * @param error 取得できなかった理由（取得できれば {@code null}）
     * @param checkedAt 調べた日時
     */
    public record Capacity(Long totalBytes, Long usableBytes, String error, LocalDateTime checkedAt) {}
}
