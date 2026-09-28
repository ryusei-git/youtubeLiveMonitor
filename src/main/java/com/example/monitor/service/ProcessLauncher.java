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
     * <p><b>見るのは、このアプリが起動する種類のプロセスだけ</b>：実行ファイル名が {@code yt-dlp}・
     * {@code ffmpeg}・{@code ffprobe} のものと、Python の処理系で引数にファイル名が {@code yt-dlp} のものを含むもの
     * （yt-dlp は Python のスクリプトなので、実行ファイルは Python になる）。シェル・{@code grep}・{@code tail}・
     * エディタなどは見ない。呼び出し元（{@link RecordingReconciler}・{@link RecordingFileService}・
     * {@link OrphanedPreviewService}）が探しているのはどれもこのアプリが起動した録画・取得・詰め替えのプロセスで、
     * 動画 ID やチャンネル ID を含むだけの無関係なプロセスで「進行中」と判定すると、補正や掃除が見送られる
     * （実際に発生した：録画の yt-dlp を止めた直後の「今すぐチェック」で、動画 ID を {@code grep} している
     * シェルがあったため補正されなかった。yt-dlp の出力は {@code logs/yt-dlp/<動画ID>.log} なので、
     * {@code tail -f} で様子を見ているだけでも同じことが起きる）。yt-dlp だけに絞らないのは、
     * {@link RecordingSalvager} が ffmpeg で録画ファイルを詰め替えている間も掃除から守るため。
     *
     * @param commandLineFragment 探したい文字列（録画なら動画 ID）
     * @return 該当するプロセスが動いていれば {@code true}
     */
    boolean isRunningWithCommandLineContaining(String commandLineFragment);

    /**
     * 指定した文字列をコマンドラインに含む yt-dlp のプロセスを探す。
     *
     * <p>チャンネルの削除で、そのチャンネルの録画を止めるのに使う（{@link MonitoredChannelService#remove(Long)}）。
     * 端末保存の期限切れの掃除（{@link DeviceDownloadService#purgeExpired()}）でも、再起動前に始めた取得の
     * yt-dlp を一時フォルダーのパスで探して止めるのに使う。どちらも、アプリが追跡中のプロセスだけでなく、
     * 再起動で追跡を失ったプロセスも止めるため、{@link Process} ではなく OS から引いた {@link ProcessHandle} を返す。
     *
     * <p>{@link #isRunningWithCommandLineContaining(String)} と同じく、動画 ID を含むだけの {@code grep}・
     * {@code tail} などは対象にしない。こちらは止めるための検索なので、さらに <b>yt-dlp だけ</b>に絞る。
     * {@code ffmpeg} まで含めると、{@link RecordingSalvager} の詰め替えまで止めてしまう。yt-dlp の子の ffmpeg は、
     * 止める側（{@link com.example.monitor.util.ProcessTermination#terminateTreeAndAwait}）が子孫として止める。
     *
     * @param commandLineFragment 探したい文字列（録画なら出力先の {@code <チャンネルID>/<動画ID>.%(ext)s}、
     *                            端末保存の掃除なら一時フォルダーの絶対パス）
     * @return 該当するプロセス。無ければ空
     */
    List<ProcessHandle> findYtDlpProcessesWithCommandLineContaining(String commandLineFragment);
}
