package com.example.monitor.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ProcessLauncher} の実運用実装。{@link ProcessBuilder} をそのまま使う。
 *
 * <p>標準エラー出力を標準出力にまとめる（{@code redirectErrorStream}）ことで、
 * yt-dlp の進捗ログとエラーログの両方を 1 本のストリームとして扱えるようにしている。
 */
@Component
public class DefaultProcessLauncher implements ProcessLauncher {

    /** 実行ファイル名だけで、このアプリが起動する種類のプロセスと分かるもの。 */
    private static final Set<String> WORKER_EXECUTABLES = Set.of("yt-dlp", "ffmpeg", "ffprobe");

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
                // 対象を絞る理由は ProcessLauncher#isRunningWithCommandLineContaining の JavaDoc を参照
                .filter(DefaultProcessLauncher::isWorkerProcess)
                .map(ProcessHandle.Info::commandLine)
                // コマンドラインは OS やパーミッションによっては取得できないため、取れたものだけを見る
                .flatMap(Optional::stream)
                .anyMatch(commandLine -> commandLine.contains(commandLineFragment));
    }

    @Override
    public List<ProcessHandle> findYtDlpProcessesWithCommandLineContaining(String commandLineFragment) {
        return ProcessHandle.allProcesses()
                .filter(handle -> isYtDlp(handle.info()) && handle.info().commandLine()
                        .map(commandLine -> commandLine.contains(commandLineFragment))
                        .orElse(false))
                .toList();
    }

    /**
     * このアプリが起動する種類のプロセス（yt-dlp・ffmpeg・ffprobe）かどうか。
     *
     * <p>実行ファイルが取れないプロセスは対象外にしてよい。このアプリが起動したプロセスは同じユーザーで動くため、
     * 実行ファイルを取れる（取れないのは他のユーザーのプロセスや、終了して回収待ちのプロセスなど）。
     *
     * <p>package-private なのは、テスト（{@code DefaultProcessLauncherTest}）から直接呼ぶため。
     * 呼び出し元が使う {@code ProcessHandle.allProcesses()} は差し替えられないので、判定だけを取り出して確かめている。
     */
    static boolean isWorkerProcess(ProcessHandle.Info info) {
        String executable = info.command().map(DefaultProcessLauncher::fileName).orElse("");
        return WORKER_EXECUTABLES.contains(executable) || isYtDlp(info);
    }

    /**
     * yt-dlp のプロセスかどうか。実行ファイルが yt-dlp のものと、Python の処理系で引数に yt-dlp を含むもの。
     *
     * <p>package-private なのは、テスト（{@code DefaultProcessLauncherTest}）から直接呼ぶため。
     * 呼び出し元が使う {@code ProcessHandle.allProcesses()} は差し替えられないので、判定だけを取り出して確かめている。
     */
    static boolean isYtDlp(ProcessHandle.Info info) {
        String executable = info.command().map(DefaultProcessLauncher::fileName).orElse("");
        if ("yt-dlp".equals(executable)) {
            return true;
        }
        // yt-dlp は Python のスクリプトなので、実行ファイルは Python の処理系になり、yt-dlp は引数の側に入る
        return executable.startsWith("python") && info.arguments().stream()
                .flatMap(Arrays::stream)
                .map(DefaultProcessLauncher::fileName)
                .anyMatch("yt-dlp"::equals);
    }

    /** パスの最後の要素。{@code /} のように要素が無いものは空文字にする。 */
    private static String fileName(String path) {
        Path name = Path.of(path).getFileName();
        return name == null ? "" : name.toString();
    }
}
