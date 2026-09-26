package com.example.monitor.cli;

import com.example.monitor.entity.Recording;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.RecordingFileService;
import com.example.monitor.sound.EarKissDetector;
import com.example.monitor.sound.EarKissModel;
import com.example.monitor.sound.PcmDecoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code sound detect} コマンド。録画を指定して耳キスの候補を探し、結果を表示する。
 *
 * <p>実行例:
 * <pre>{@code
 * java -jar app.jar sound detect 23 --from 0 --to 600
 * }</pre>
 *
 * <p><b>DB には保存しない。</b>管理者が検出器の結果をその場で確かめるためのもの（保存と自動の見回りは Issue #469）。
 * 時刻はミリ秒まで出す。候補は 5ms（帯域フレーム 1 つ）単位で決まり、参照実装の一覧と突き合わせるのに秒では粗いため。
 *
 * <p>{@code --from}・{@code --to} で範囲を絞ると、背景・前後 3 秒の特徴・候補の上限（1 時間あたり 40 件）が
 * その範囲だけで決まるので、録画全体で探したときと端の近くの結果が変わりうる。
 */
@Component
@Command(
        name = "detect",
        mixinStandardHelpOptions = true,
        description = "録画の耳キスの候補を探して表示する（保存はしない）")
@RequiredArgsConstructor
public class SoundDetectCommand implements Callable<Integer> {

    private final RecordingRepository recordingRepository;
    private final RecordingFileService recordingFileService;

    @Parameters(index = "0", description = "録画の番号（録画一覧の ID）")
    private Long recordingId;

    @Option(names = "--from", description = "探し始める位置（秒）。省略すると先頭から")
    private Double fromSeconds;

    @Option(names = "--to", description = "探し終える位置（秒。録画の先頭から数える）。省略すると最後まで")
    private Double toSeconds;

    /**
     * 候補を探して 1 行ずつ表示する。
     *
     * @return 成功なら 0、録画やファイルが無い・範囲の指定が誤っている・ffmpeg が失敗した場合は 1
     */
    @Override
    public Integer call() {
        double from = fromSeconds == null ? 0 : fromSeconds;
        if (from < 0 || (toSeconds != null && toSeconds <= from)) {
            System.err.println("--from は 0 以上、--to は --from より後を指定してください");
            return 1;
        }
        Optional<Recording> recording = recordingRepository.findById(recordingId);
        if (recording.isEmpty()) {
            System.err.println("録画が見つかりません: ID " + recordingId);
            return 1;
        }
        Path file = recordingFileService.resolveFilePath(recording.get());
        if (!Files.isRegularFile(file)) {
            System.err.println("録画ファイルがありません: " + file);
            return 1;
        }

        long started = System.nanoTime();
        EarKissModel model = EarKissModel.load();
        EarKissDetector.Result result;
        try (InputStream pcm = PcmDecoder.open(file, fromSeconds, toSeconds)) {
            result = new EarKissDetector(model).detect(pcm);
        } catch (IOException e) {
            System.err.println("音声を読めませんでした: " + e.getMessage());
            return 1;
        }
        double elapsedSeconds = (System.nanoTime() - started) / 1e9;

        // 範囲を絞ったときも、録画の先頭からの時刻で出す
        long offsetMs = Math.round(from * 1000);
        for (EarKissDetector.FinalCandidate candidate : result.finals()) {
            System.out.printf("%s %.6f%n", formatPosition(offsetMs + candidate.positionMs()), candidate.score());
        }
        System.out.printf("候補 %d 件（%s・音声 %.1f 分・処理 %.1f 秒）%n", result.finals().size(), model.version(),
                result.bandFrames() / 200.0 / 60, elapsedSeconds);
        return 0;
    }

    /** ミリ秒を {@code h:mm:ss.SSS} にする。 */
    private static String formatPosition(long ms) {
        return String.format("%d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }
}
