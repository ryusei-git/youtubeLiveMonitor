package com.example.monitor.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.io.IOException;

/** 保存先が未作成でも、その親が属するボリュームの空き容量を確認するための処理。 */
public final class DiskSpaceUtils {
    private DiskSpaceUtils() {}
    /** 取得失敗をゼロ容量と誤認させないため、値と失敗理由を分ける。 */
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
    /** 録画ファイルの合計とは別の指標として表示する。 */
    public record Capacity(Long totalBytes, Long usableBytes, String error, LocalDateTime checkedAt) {}
}
