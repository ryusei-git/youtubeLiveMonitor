package com.example.monitor.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** 容量表示のために、ファイルまたはディレクトリ配下の実サイズを求める処理。 */
public final class DirectorySizeUtils {
    private DirectorySizeUtils() {}

    /**
     * ファイルならそのサイズ、ディレクトリなら配下の全ファイルの合計を返す。
     *
     * <p>{@code Files.walk} ではなく {@code walkFileTree} を使うのは、途中で読めないファイルや
     * ディレクトリが 1 つあっても例外で全体を失敗させず、そこだけ飛ばして数え続けるため
     * （{@code Files.walk} はストリームの途中で {@code UncheckedIOException} を投げて打ち切られる）。
     *
     * @param path 対象のファイルまたはディレクトリ
     * @return 合計サイズ（バイト）。存在しなければ 0
     */
    public static long sizeOf(Path path) {
        if (!Files.exists(path)) return 0;
        long[] total = {0};
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // visitFileFailed で吸収するので通常は来ない。来ても数えられた分を返す
        }
        return total[0];
    }
}
