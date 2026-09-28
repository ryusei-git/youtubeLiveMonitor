package com.example.monitor.service;

import com.example.monitor.util.ProcessTermination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 録画の保存先ディレクトリを、OS 本来のフォルダ選択ダイアログで選ばせる。
 *
 * <p>ブラウザのネイティブなフォルダ選択（{@code <input type="file" webkitdirectory>} 等）は
 * アップロード用途専用で、選んだフォルダの絶対パスを取得できない（ブラウザが意図的に隠している）。
 * このアプリは個人用の自己ホスト型ツールで、ブラウザとサーバーが同じマシン上にある運用を
 * 前提としている。そこでサーバー側のプロセスから直接 OS のフォルダ選択ダイアログを起動し、
 * 選ばれたパスをそのまま受け取る方式にしている。
 *
 * <p><b>この方式が成立する前提</b>: サーバープロセスが GUI セッション（Linux なら
 * {@code DISPLAY}/{@code WAYLAND_DISPLAY}、Windows なら対話的デスクトップセッション）を
 * 持っていること。Tailscale 等でリモートから使う場合、ダイアログは<b>サーバー側の画面に</b>
 * 表示される（ブラウザを開いている端末ではない）ため、リモート利用時はこの機能は実質使えない。
 * その場合は画面の保存先の入力欄に直接パスを入力すること。
 *
 * <p>OS ごとに異なる外部コマンドを {@link ProcessLauncher} 経由で起動する（{@code yt-dlp}・
 * {@code ffmpeg} と同じ仕組み）。
 * <ul>
 *   <li>Linux: {@code zenity --file-selection --directory}</li>
 *   <li>Windows: PowerShell 経由で {@code System.Windows.Forms.FolderBrowserDialog}</li>
 * </ul>
 *
 * <h2>打ち切りを効かせるため、出力は別スレッドで読む</h2>
 * 以前は標準出力を {@code readLine()} で EOF まで読み切ってから {@code waitFor} で打ち切りを待っていた。
 * EOF はダイアログが閉じる（プロセスが終わる）まで来ないので、打ち切りに届かなかった
 * （{@link ExternalCommandRunner} のクラス JavaDoc にある誤りと同じ）。リモートから「参照...」を押すと、
 * ダイアログはサーバーの画面に出て誰も閉じられない。そのため要求のスレッドと zenity／PowerShell が、
 * 押した回数だけ残り続けた。今は読み取りを仮想スレッドで先に始め、
 * {@code DIALOG_RESPONSE_TIMEOUT_SECONDS}（300 秒）を過ぎたらプロセスを強制終了する。
 *
 * <h2>出力には標準エラーが混ざる</h2>
 * {@link ProcessLauncher#launch(List)} は標準エラーを標準出力にまとめる。GTK3 の zenity は、
 * ダイアログを出しただけで {@code Gtk-Message: ... GtkDialog mapped without a transient parent} のような
 * 警告を標準エラーへ出すことがある。出力全体を返すと警告ごと入力欄に入るので、
 * <b>実在するディレクトリの絶対パスになっている最後の行だけ</b>を返す。
 * 標準エラーを分けて起動しないのは、キャンセルや表示できなかったときの理由をまとめた出力のまま
 * ログに残せば足りるため。また、キャンセルと「表示できなかった」は終了コードでも標準エラーの有無でも
 * 見分けられない（GUI の無いサーバーでは zenity がキャンセルと同じ終了コード 1 で終わり、
 * GTK の警告はキャンセルでも出る）。そのためどちらも空を返し、出力を INFO でログに残す。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NativeDirectoryPickerService {

    /**
     * ダイアログの応答待ち上限（秒）。
     *
     * <p>人がダイアログを操作して選び終えるまで待つため、{@link VideoMetadataExtractor}等の
     * 自動処理向けタイムアウト（数十秒）より大幅に長くしている。
     *
     * <p>この時間を過ぎたらダイアログのプロセスを強制終了し、キャンセルと同じく空を返す。
     * リモートから押されてダイアログが誰にも見えないときでも、要求のスレッドを返すため。
     */
    private static final long DIALOG_RESPONSE_TIMEOUT_SECONDS = 300;

    /**
     * ダイアログが閉じた後、出力を読み終えるまで待つ上限（秒）。
     *
     * <p>プロセスが終われば出力はすぐ EOF になるので短くてよい。ここで無期限に待つと、
     * {@code DIALOG_RESPONSE_TIMEOUT_SECONDS} で打ち切った意味が無くなる。
     */
    private static final long OUTPUT_DRAIN_TIMEOUT_SECONDS = 5;

    private final ProcessLauncher processLauncher;

    /**
     * OS のフォルダ選択ダイアログを表示し、選ばれたパスを返す。
     *
     * @param initialDirectory ダイアログを開く初期ディレクトリ。{@code null}または空文字なら OS の既定
     * @return 選ばれたパス（実在するディレクトリの絶対パス）。キャンセルされた場合・
     *         300 秒（{@code DIALOG_RESPONSE_TIMEOUT_SECONDS}）以内に閉じられなかった場合・
     *         ダイアログを表示できずに終わった場合（GUI の無いサーバーの zenity など）は {@link Optional#empty()}
     * @throws IllegalStateException ダイアログを起動できなかった場合（{@code zenity} が入っていない等）と、
     *         ダイアログが正常に終わったのに出力に実在するディレクトリの絶対パスが無かった場合
     */
    public Optional<String> pickDirectory(String initialDirectory) {
        List<String> command = buildCommand(initialDirectory);

        Process process;
        try {
            process = processLauncher.launch(command);
        } catch (IOException e) {
            log.error("フォルダ選択ダイアログを起動できませんでした: command={}", command, e);
            throw new IllegalStateException(
                    "フォルダ選択ダイアログを起動できませんでした（" + command.get(0)
                            + " が必要です。GUI セッションが無いリモート実行では使えません）: " + e.getMessage());
        }

        // 出力の読み取りを先に別スレッドで始めてから waitFor に入る（理由はクラスの JavaDoc）
        DialogOutputReader reader = new DialogOutputReader(process);
        Thread readerThread = Thread.ofVirtual().name("directory-picker-reader").start(reader);

        try {
            if (!process.waitFor(DIALOG_RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminateAndAwait(process, readerThread);
                log.warn("フォルダ選択ダイアログが {} 秒以内に閉じられなかったため打ち切りました",
                        DIALOG_RESPONSE_TIMEOUT_SECONDS);
                return Optional.empty();
            }
            if (!readerThread.join(Duration.ofSeconds(OUTPUT_DRAIN_TIMEOUT_SECONDS))) {
                terminateAndAwait(process, readerThread);
                log.warn("フォルダ選択ダイアログの出力を読み終えられませんでした");
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            // 割り込み状態を戻すのは後始末の後（ExternalCommandRunner#run と同じ理由）
            terminateAndAwait(process, readerThread);
            Thread.currentThread().interrupt();
            log.warn("フォルダ選択ダイアログの完了待ちが中断されました");
            return Optional.empty();
        }

        if (reader.failure != null) {
            log.warn("フォルダ選択ダイアログの出力読み取りに失敗しました", reader.failure);
            return Optional.empty();
        }

        List<String> lines = reader.lines.stream().filter(line -> !line.isBlank()).toList();
        // ログを 1 行に収めるため、出力の行は " / " でつなぐ
        String outputForLog = String.join(" / ", lines);
        int exitCode = process.exitValue();
        if (exitCode != 0 || lines.isEmpty()) {
            // キャンセルと「表示できなかった」は終了コードでは見分けられない（クラスの JavaDoc）。
            // 理由を後から追えるよう、出力（標準エラーを含む）を残す
            log.info("フォルダ選択ダイアログがパスを返さずに終わりました（キャンセル、または表示できなかった）: exitCode={}, 出力={}",
                    exitCode, outputForLog);
            return Optional.empty();
        }

        // 標準エラーの警告が混ざるので、実在するディレクトリの絶対パスになっている最後の行を返す
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (isExistingAbsoluteDirectory(lines.get(i))) {
                return Optional.of(lines.get(i));
            }
        }
        log.warn("フォルダ選択ダイアログの出力に、実在するディレクトリの絶対パスがありませんでした: 出力={}", outputForLog);
        throw new IllegalStateException(
                "フォルダ選択ダイアログの出力から保存先のパスを読み取れませんでした。保存先の入力欄に直接パスを入力してください");
    }

    /**
     * プロセスを強制終了し、実際に終わったことを確かめてから読み取りスレッドを片付ける。
     * 中身と割り込みの扱いは {@link ExternalCommandRunner} の同名メソッドと同じ（理由はそちらの JavaDoc）。
     *
     * @param process      対象のプロセス
     * @param readerThread 出力を読み取っているスレッド
     */
    private void terminateAndAwait(Process process, Thread readerThread) {
        boolean interrupted = ProcessTermination.destroyForciblyAndAwait(process);

        try {
            readerThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (readerThread.isAlive()) {
            readerThread.interrupt();
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ダイアログの出力の 1 行が、実在するディレクトリの絶対パスかどうか。
     *
     * <p>出力には標準エラーが混ざる（クラスの JavaDoc）。GTK の警告
     * （{@code Gtk-Message: 12:34:56.789: ...}）は Linux では絶対パスにならず、Windows ではパスとして
     * 解釈できずに {@link InvalidPathException} になる。どちらもここで除く。
     * Linux で Java の文字コードが UTF-8 でないとき、日本語の行も {@link InvalidPathException} になる。
     *
     * @param line 出力の 1 行
     * @return 実在するディレクトリの絶対パスなら {@code true}
     */
    private static boolean isExistingAbsoluteDirectory(String line) {
        try {
            Path path = Path.of(line);
            return path.isAbsolute() && Files.isDirectory(path);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * OS に応じたフォルダ選択コマンドを組み立てる。
     *
     * @param initialDirectory ダイアログを開く初期ディレクトリ
     * @return 実行するコマンド
     */
    private List<String> buildCommand(String initialDirectory) {
        if (isWindows()) {
            return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    windowsScript(initialDirectory));
        }

        if (initialDirectory != null && !initialDirectory.isBlank()) {
            String startPath = initialDirectory.endsWith("/") ? initialDirectory : initialDirectory + "/";
            return List.of("zenity", "--file-selection", "--directory",
                    "--title=録画の保存先を選択", "--filename=" + startPath);
        }
        return List.of("zenity", "--file-selection", "--directory", "--title=録画の保存先を選択");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Windows 向けのフォルダ選択スクリプトを組み立てる。
     *
     * <p>{@code FolderBrowserDialog} は Windows Explorer と同じダイアログを表示する
     * 標準の .NET コンポーネントで、追加のインストールは不要。
     *
     * <p>選ばれたパスは {@code Write-Output} ではなく、UTF-8 のバイト列にして標準出力へ直接書く。
     * Windows PowerShell 5.1 は {@code Write-Output} の結果をパイプへ OEM のコードページ
     * （日本語の Windows では CP932）で書くため、UTF-8 として読むと日本語のフォルダ名が化ける
     * （Java から起動して確かめた：「画」が CP932 の {@code 89 e6} で届き、バイト列を直接書くと
     * UTF-8 の {@code e7 94 bb} になった）。{@code [Console]::OutputEncoding} を UTF-8 にしても直るが、
     * これはコンソールのコードページそのものを変えるので、同じコンソールにつながったほかのプロセスへ響きうる
     * （Git Bash から続けて起動した PowerShell どうしでは実際に響いた）。
     * バイト列を直接書けば、コンソールの設定には触れない。
     *
     * @param initialDirectory ダイアログを開く初期ディレクトリ
     * @return PowerShell に渡すスクリプト本体
     */
    private String windowsScript(String initialDirectory) {
        // PowerShell の単一引用符リテラル内でのエスケープは ' を '' に置き換える
        String escapedPath = initialDirectory == null ? "" : initialDirectory.replace("'", "''");
        return "Add-Type -AssemblyName System.Windows.Forms; "
                + "$dialog = New-Object System.Windows.Forms.FolderBrowserDialog; "
                + "$dialog.SelectedPath = '" + escapedPath + "'; "
                + "if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { "
                + "$bytes = [System.Text.Encoding]::UTF8.GetBytes($dialog.SelectedPath + [char]10); "
                + "$stdout = [Console]::OpenStandardOutput(); "
                + "$stdout.Write($bytes, 0, $bytes.Length); $stdout.Flush() }";
    }

    /**
     * ダイアログの出力（標準出力と標準エラーをまとめたもの）を行ごとに読み切る。
     *
     * <p>結果はフィールドに置き、{@link Thread#join} の後に読む。join の完了が書き込みとの間に
     * happens-before を作るので、{@code volatile} は要らない（{@link ExternalCommandRunner} の読み取りと同じ考え方）。
     */
    private static final class DialogOutputReader implements Runnable {
        private final Process process;
        private final List<String> lines = new ArrayList<>();
        private IOException failure;

        DialogOutputReader(Process process) {
            this.process = process;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                failure = e;
            }
        }
    }
}
