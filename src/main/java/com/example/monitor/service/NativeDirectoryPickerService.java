package com.example.monitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
     */
    private static final long DIALOG_RESPONSE_TIMEOUT_SECONDS = 300;

    private final ProcessLauncher processLauncher;

    /**
     * OS のフォルダ選択ダイアログを表示し、選ばれたパスを返す。
     *
     * @param initialDirectory ダイアログを開く初期ディレクトリ。{@code null}または空文字なら OS の既定
     * @return 選ばれた絶対パス。キャンセルされた場合は {@link Optional#empty()}
     * @throws IllegalStateException ダイアログを起動できなかった場合
     *         （{@code zenity}未インストール、GUI セッションが無い等）
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

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }

            if (!process.waitFor(DIALOG_RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("フォルダ選択ダイアログが時間内に閉じられなかったため打ち切りました");
                return Optional.empty();
            }

            // ダイアログでの「キャンセル」は正常な操作結果であり、失敗ではない。
            // zenity・PowerShellのどちらも、キャンセル時は非0の終了コードで出力も空になる
            if (process.exitValue() != 0 || output.isEmpty()) {
                log.debug("フォルダ選択ダイアログがキャンセルされました");
                return Optional.empty();
            }
            return Optional.of(output.toString().strip());

        } catch (IOException e) {
            log.warn("フォルダ選択ダイアログの出力読み取りに失敗しました", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("フォルダ選択ダイアログの完了待ちが中断されました");
            return Optional.empty();
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
                + "Write-Output $dialog.SelectedPath }";
    }
}
