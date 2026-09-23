package com.example.monitor.service;

import java.io.IOException;
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
