package com.example.monitor.service;

import com.example.monitor.entity.Recording;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.DatabaseUpdateVerifier;
import com.example.monitor.util.ProcessTermination;
import com.example.monitor.util.YtDlpLogFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 録画から音声だけの MP3 を作り、録画と同じフォルダーに {@code <動画ID>.mp3} として置く（#819）。
 *
 * <p>将来の音声だけの配信に備えるためのもの。録画の後始末（{@link RecordingReconciler}）が新しい録画に、
 * CLI の {@code recording mp3} が管理者の指定した録画に使う。
 *
 * <h2>音質と ffmpeg の起動のしかた</h2>
 * <ul>
 *   <li>ステレオのまま {@code libmp3lame} の CBR（既定 192kbps）にする。ASMR は左右の音（バイノーラル）が
 *       大事なので、モノラルにしない。</li>
 *   <li>{@code nice -n 19} で動かす。急がない後回しの処理で、録画（yt-dlp・ffmpeg）とサービスの方が
 *       大事なため（{@code PcmDecoder} と同じ。{@code nice} は ffmpeg を exec で起動し直すので、
 *       {@link Process} は ffmpeg を指す）。</li>
 *   <li>出力（{@code -v error} なので失敗の理由だけ）は JVM へのパイプにせず、その動画の yt-dlp のログ
 *       （{@link YtDlpLogFile}）へ追記する（{@code docs/pitfalls.md}「外部プロセスの出力を JVM への
 *       パイプにすると…」）。置き場所を増やさずに済み、録画を消せば一緒に消え、1 本の経緯を 1 か所で
 *       追えるため。</li>
 * </ul>
 *
 * <h2>成否は終了コードではなく、出来た MP3 の長さで決める</h2>
 * 一時ファイル（{@code <動画ID>.mp3.part}）に書き、元の音声とおおむね同じ長さ（{@link #roughlyMatches}）
 * のときだけ {@code <動画ID>.mp3} へ名前を変える。途中で止まった（時間切れ・容量不足・アプリの停止）
 * 書きかけを完成と見誤らないため（{@code docs/pitfalls.md}「録画の成否は終了コードではなく…」と
 * 同じ考え方）。
 *
 * <h2>同じ動画を扱うプロセスがあれば始めない</h2>
 * サービス（後始末）と CLI は別の JVM なので、同じ録画の MP3 を同時に作りうる。2 本の ffmpeg が
 * 同じ一時ファイルへ書くと、混ざった出力を完成として残しうる。そこで、動画 ID をコマンドラインに含む
 * プロセス（{@link ProcessLauncher#isRunningWithCommandLineContaining}）があれば始めない。
 * アプリの停止をまたいで残った ffmpeg も、これで避けられる。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingAudioExtractor {

    /** MP3 の拡張子。 */
    private static final String MP3_EXTENSION = ".mp3";

    /**
     * 書きかけの MP3 に付ける接尾辞。完成するまで本来の名前にしない
     * （{@code RecordingSalvager} の一時ファイルと同じ考え方）。
     */
    private static final String WORK_FILE_SUFFIX = ".part";

    /**
     * 長さの違いをどこまで同じとみなすか（元の音声の長さに対する割合）。
     *
     * <p>途中で止まった ffmpeg の書きかけは大きく短くなる（1 時間の録画を 8 秒で止めると 830 秒）。
     * 一方で、秒への切り捨てやエンコーダーの遅延、壊れたパケットの読み飛ばしによる違いはごく小さい。
     * その間を取って 1% にしている。
     */
    private static final double DURATION_TOLERANCE_RATIO = 0.01;

    /** 長さの違いを同じとみなす下限（秒）。短い録画で、秒への切り捨ての違いだけで失敗にしないため。 */
    private static final int MIN_DURATION_TOLERANCE_SECONDS = 2;

    /**
     * 時間の上限を決める割合（元の音声の長さ ÷ この値）。実測は 1 時間の音声で約 37 秒（長さの約 1%）
     * なので、録画やビルドと CPU を取り合って遅くなっても、ふつうは届かない
     * （{@code SoundDetectionService} と同じ決め方）。
     */
    private static final int TIMEOUT_DIVISOR = 10;

    /** 時間の上限に足す秒数。短い録画でも、ffmpeg の起動や読み込みの待ちで誤って止めないため。 */
    private static final long TIMEOUT_EXTRA_SECONDS = 600;

    private final RecordingFileService recordingFileService;
    private final RecordingRepository recordingRepository;
    private final VideoMetadataExtractor videoMetadataExtractor;
    private final ProcessLauncher processLauncher;

    /**
     * MP3 のビットレート（{@code -b:a} に渡す値）。final にしない理由は {@code RecordingSalvager} の
     * {@code minFreeGb} と同じ。
     */
    @Value("${monitor.recording.mp3-bitrate:192k}")
    private String bitrate = "192k";

    /**
     * 録画 1 本から MP3 を作り、そのパスを録画履歴に記録する。
     *
     * <p>同じ録画の MP3 が既にあっても作り直す（CLI で管理者が指定したとき）。
     *
     * @param recording 対象の録画履歴。再生できる録画（完了・途中まで）であること
     * @return 結果
     */
    public Mp3Outcome createMp3(Recording recording) {
        String videoId = recording.getVideoId();
        Optional<Path> existingFile = recordingFileService.resolveExistingFile(recording);
        if (existingFile.isEmpty()) {
            log.warn("録画ファイルが無いため MP3 を作れませんでした: id={}, video={}", recording.getId(), videoId);
            return Mp3Outcome.FAILED;
        }
        if (processLauncher.isRunningWithCommandLineContaining(videoId)) {
            log.info("この動画を扱うプロセスが動いているため MP3 の作成を見送ります: id={}, video={}",
                    recording.getId(), videoId);
            return Mp3Outcome.IN_USE;
        }

        Path videoFile = existingFile.get();
        Optional<Integer> sourceSeconds = videoMetadataExtractor.extractAudioStreamDurationSeconds(videoFile);
        if (sourceSeconds.isEmpty()) {
            log.warn("録画の音声の長さを読めないため MP3 を作れませんでした（音声が無い録画かもしれません）: "
                    + "id={}, video={}", recording.getId(), videoId);
            return Mp3Outcome.FAILED;
        }

        Path mp3File = videoFile.resolveSibling(videoId + MP3_EXTENSION);
        Path workFile = mp3File.resolveSibling(mp3File.getFileName() + WORK_FILE_SUFFIX);
        long started = System.nanoTime();
        Integer exitCode = runFfmpeg(videoFile, workFile, videoId, sourceSeconds.get());
        Optional<Integer> mp3Seconds = videoMetadataExtractor.extractAudioStreamDurationSeconds(workFile);

        if (mp3Seconds.isEmpty() || !roughlyMatches(mp3Seconds.get(), sourceSeconds.get())) {
            log.warn("MP3 を作れませんでした: id={}, video={}, 終了コード={}, 元の音声={}秒, MP3={}秒, "
                            + "ffmpeg の出力={}", recording.getId(), videoId, exitCode, sourceSeconds.get(),
                    mp3Seconds.orElse(null), YtDlpLogFile.of(videoId));
            deleteQuietly(workFile);
            return Mp3Outcome.FAILED;
        }

        try {
            Files.move(workFile, mp3File, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("MP3 の名前を変えられませんでした: id={}, video={}", recording.getId(), videoId, e);
            deleteQuietly(workFile);
            return Mp3Outcome.FAILED;
        }

        String audioPath = recordingFileService.toRelativePath(mp3File);
        DatabaseUpdateVerifier.verify(recordingRepository.updateAudioPath(recording.getId(), audioPath),
                "MP3 のパスの記録", recording.getId());
        log.info("録画の MP3 を作りました: id={}, video={}, 長さ={}秒, 処理={}秒", recording.getId(), videoId,
                mp3Seconds.get(), TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started));
        return Mp3Outcome.CREATED;
    }

    /**
     * ffmpeg で MP3 を書き、終わるのを待つ。
     *
     * <p>時間の上限（{@link #TIMEOUT_DIVISOR}）を超えたら止める。待っている間に割り込まれたときも止める
     * （割り込み状態は止め終えてから戻す。先に戻すと、終了を確かめる待ちがすぐ例外で返るため。
     * {@link ProcessTermination} 参照）。
     *
     * @param videoFile     元の録画ファイル
     * @param workFile      書き出す一時ファイル
     * @param videoId       動画 ID（出力の追記先とログに使う）
     * @param sourceSeconds 元の音声の長さ（秒）。時間の上限を決めるのに使う
     * @return 終了コード。起動できなかった・時間内に終わらず止めた・割り込まれた場合は {@code null}
     */
    private Integer runFfmpeg(Path videoFile, Path workFile, String videoId, int sourceSeconds) {
        List<String> command = List.of(
                "nice", "-n", "19",
                "ffmpeg", "-y", "-nostdin", "-v", "error", "-nostats",
                "-i", videoFile.toString(),
                "-vn", "-c:a", "libmp3lame", "-b:a", bitrate,
                // 一時ファイルの拡張子（.part）からは形式が決まらないので明示する
                "-f", "mp3",
                workFile.toString());

        Process process;
        try {
            process = processLauncher.launch(command, YtDlpLogFile.of(videoId));
        } catch (IOException e) {
            log.warn("MP3 を作る ffmpeg を起動できませんでした（nice・ffmpeg が無い可能性があります）: video={}",
                    videoId, e);
            return null;
        }

        long timeoutSeconds = sourceSeconds / TIMEOUT_DIVISOR + TIMEOUT_EXTRA_SECONDS;
        boolean interrupted = false;
        try {
            if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                return process.exitValue();
            }
            log.warn("MP3 の作成が時間の上限（{}秒）を超えたため止めます: video={}", timeoutSeconds, videoId);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        interrupted |= ProcessTermination.destroyForciblyAndAwait(process);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * MP3 の長さが元の音声とおおむね同じか。
     *
     * @param mp3Seconds    MP3 の長さ（秒）
     * @param sourceSeconds 元の音声の長さ（秒）
     * @return 違いが元の長さの {@link #DURATION_TOLERANCE_RATIO}
     *         （{@link #MIN_DURATION_TOLERANCE_SECONDS} 秒以上）以内なら {@code true}
     */
    private static boolean roughlyMatches(int mp3Seconds, int sourceSeconds) {
        double tolerance = Math.max(MIN_DURATION_TOLERANCE_SECONDS, sourceSeconds * DURATION_TOLERANCE_RATIO);
        return Math.abs(mp3Seconds - sourceSeconds) <= tolerance;
    }

    /**
     * 書きかけの後始末。消せなくても続ける（次の作成で {@code -y} が上書きする）。
     *
     * @param file 削除するファイル
     */
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("書きかけの MP3 を削除できませんでした: {}", file, e);
        }
    }

    /** {@link #createMp3} の結果。 */
    public enum Mp3Outcome {
        /** MP3 を作り、録画履歴に記録した。 */
        CREATED,
        /** この動画を扱うプロセスが動いていたので始めなかった。後でやり直せばよい。 */
        IN_USE,
        /** 作れなかった（理由はログ）。同じファイルのままやり直しても、ふつうは同じ結果になる。 */
        FAILED
    }
}
