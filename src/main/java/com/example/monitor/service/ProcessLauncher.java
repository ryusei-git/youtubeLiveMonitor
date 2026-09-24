package com.example.monitor.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 外部プロセスを起動する窓口。
 *
 * <p>{@link StreamRecorder} が {@code new ProcessBuilder(...).start()} を直接呼ぶと、
 * テストのたびに実際に yt-dlp プロセスが起動してしまいテストできない。
 * インターフェースとして切り出すことで、テスト時はモックの {@link Process} を返す実装に
 * 差し替えられるようにしている（{@link com.example.monitor.service.LiveStreamDetector} の
 * {@code HttpClient} 注入と同じ考え方）。
 */
public interface ProcessLauncher {

    /**
     * 指定したコマンドで外部プロセスを起動する。
     *
     * @param command 実行するコマンドと引数
     * @return 起動したプロセス
     * @throws IOException 実行ファイルが見つからない等、起動に失敗した場合
     */
    Process launch(List<String> command) throws IOException;

    /**
     * 外部プロセスを起動し、標準出力と標準エラーをまとめて {@code outputFile} へ追記させる。
     *
     * <p><b>JVM より長く動き続けるべきプロセス（録画）はこちらを使う。</b>
     * {@link #launch(List)} は出力を JVM へのパイプにするため、JVM が止まるとパイプの読み手が
     * いなくなり、子プロセスは次に出力した時点で書き込みに失敗して止まる（yt-dlp は Python 製なので
     * BrokenPipeError になる。実際に録画中の再起動で映像が途中で止まり、結合もされなかった。
     * {@link StreamRecorder} のクラス JavaDoc 参照）。ファイルなら JVM が止まっても書き続けられる。
     * 短時間で終わり、出力を読んで使うもの（{@link ExternalCommandRunner}・
     * {@link NativeDirectoryPickerService}）は従来の {@link #launch(List)} のまま。
     *
     * <p>上書きではなく追記にしているのは、同じ出力先で起動し直したとき（録画の録り直し）に
     * 前回の出力を消さないため。
     *
     * @param command    実行するコマンドと引数
     * @param outputFile 出力の書き込み先。親ディレクトリが無ければ作る
     * @return 起動したプロセス。出力はファイルへ向けているため {@link Process#getInputStream()} は常に空
     * @throws IOException 実行ファイルが見つからない、出力先のファイルを作れない等、起動に失敗した場合
     */
    Process launch(List<String> command, Path outputFile) throws IOException;

    /**
     * 指定した文字列をコマンドラインに含むプロセスが、この OS 上でまだ動いているかを調べる。
     *
     * <p>録画プロセスはアプリを再起動しても独立した OS プロセスとして生き残る
     * （{@link StreamRecorder} のクラス JavaDoc 参照）。そのため「このアプリが追跡しているか」
     * （{@link StreamRecorder#isRecording(String)}）だけでは、再起動後に
     * <b>実際にはまだ録画中のプロセスを「終わっている」と誤判定してしまう</b>。
     * {@link RecordingReconciler} が録画を失敗扱いにしてよいか判断するために使う。
     *
     * @param commandLineFragment 探したい文字列（録画なら動画 ID）
     * @return 該当するプロセスが動いていれば {@code true}
     */
    boolean isRunningWithCommandLineContaining(String commandLineFragment);
}
