package com.example.monitor.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@link ProcessLauncher} の実運用実装。{@link ProcessBuilder} をそのまま使う。
 *
 * <p>標準エラー出力を標準出力にまとめる（{@code redirectErrorStream}）ことで、
 * yt-dlp の進捗ログとエラーログの両方を 1 本のストリームとして扱えるようにしている。
 */
@Component
public class DefaultProcessLauncher implements ProcessLauncher {

    @Override
    public Process launch(List<String> command) throws IOException {
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }

    @Override
    public Process launch(List<String> command, Path outputFile) throws IOException {
        // 追記先のファイルは ProcessBuilder が作るが、親ディレクトリまでは作らない
        Files.createDirectories(outputFile.toAbsolutePath().getParent());
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile.toFile()))
                .start();
    }

    @Override
    public boolean isRunningWithCommandLineContaining(String commandLineFragment) {
        return ProcessHandle.allProcesses()
                .map(ProcessHandle::info)
                .map(ProcessHandle.Info::commandLine)
                // コマンドラインは OS やパーミッションによっては取得できないため、取れたものだけを見る
                .flatMap(Optional::stream)
                .anyMatch(commandLine -> commandLine.contains(commandLineFragment));
    }
}
