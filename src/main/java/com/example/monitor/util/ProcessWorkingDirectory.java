package com.example.monitor.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * プロセスの作業フォルダーを {@code /proc/<pid>/cwd} から読む。
 *
 * <p>ダッシュボードで、動いているプロセスがどのサービスのものかを作業フォルダーで見分けるために使う。
 * 1 分ごとの記録が全プロセスについて呼ぶので、軽く読めることを優先している。
 *
 * <p><b>OSHI を使わない。</b>OSHI の {@code OSProcess.getCurrentWorkingDirectory()} も同じ readlink だが、
 * {@code OSProcess} を作ると 1 件で約 0.12MiB を確保するので、全プロセスを見るときは使わない
 * （{@code ResourceMonitorService} が全プロセスの列挙をやめたのと同じ理由。#201）。readlink だけなら、
 * 実行ユーザーの 132 件を約 13ms で読めた。JDK の {@link ProcessHandle} には作業フォルダーを取る API が無い。
 *
 * <p>読めないときは空を返し、ログは出さない（全プロセスで 1 分ごとに呼ぶので、ほかのユーザーのプロセスで
 * 毎回ログが埋まるため）。ほかのユーザーのものと dumpable でないプロセスは {@code AccessDeniedException}、
 * 終わったプロセスと {@code /proc} の無い OS は {@code NoSuchFileException} になる（この端末で確かめた）。
 * Windows では常に空になるので、呼び出し側は「判定できなかった」として扱う（例外にしないのは、
 * {@code docs/pitfalls.md}「ファイルの権限を POSIX の属性で指定すると、Windows では起動もテストもできない」と
 * 同じく、Windows の作業端末でも起動とテストを壊さないため）。
 */
public final class ProcessWorkingDirectory {

    private ProcessWorkingDirectory() {
    }

    /**
     * プロセスの作業フォルダーを返す。
     *
     * @param pid プロセス ID
     * @return 作業フォルダー。読めない（権限が無い・終わった・{@code /proc} が無い）ときは空
     */
    public static Optional<Path> of(long pid) {
        try {
            return Optional.of(Files.readSymbolicLink(Path.of("/proc", String.valueOf(pid), "cwd")));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return Optional.empty();
        }
    }
}
