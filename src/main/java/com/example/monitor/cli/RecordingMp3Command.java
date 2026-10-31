package com.example.monitor.cli;

import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.RecordingAudioExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code recording mp3} コマンド。録画を指定して、音声だけの MP3 を作る。
 *
 * <p>実行例:
 * <pre>{@code
 * java -jar app.jar recording mp3 23
 * }</pre>
 *
 * <p><b>既存の録画の MP3 を作るためのもの。</b>後始末が自動で作るのは、MP3 の機能を入れた後の録画だけ
 * （理由は {@link Recording#autoAudio}）。既存の録画は、管理者が要るものだけをこれで作る。
 * 作り方（音質・一時ファイル・成否の決め方）は自動のときと同じ（{@link RecordingAudioExtractor}）。
 * 終わるまで待つ（1 時間の音声で 40 秒ほど）。
 */
@Component
@Command(
        name = "mp3",
        mixinStandardHelpOptions = true,
        description = "録画から音声だけの MP3 を作る（録画と同じフォルダーに <動画ID>.mp3）")
@RequiredArgsConstructor
public class RecordingMp3Command implements Callable<Integer> {

    private final RecordingRepository recordingRepository;
    private final RecordingAudioExtractor recordingAudioExtractor;

    @Parameters(index = "0", description = "録画の番号（録画一覧の ID）")
    private Long recordingId;

    /**
     * MP3 を作り、結果を表示する。
     *
     * @return 作れたら 0、録画が無い・再生できない録画・作れなかった・ほかのプロセスが扱っている場合は 1
     */
    @Override
    public Integer call() {
        Optional<Recording> recording = recordingRepository.findById(recordingId);
        if (recording.isEmpty()) {
            System.err.println("録画が見つかりません: ID " + recordingId);
            return 1;
        }
        RecordingStatus status = recording.get().getStatus();
        if (status != RecordingStatus.COMPLETED && status != RecordingStatus.PARTIAL) {
            System.err.println("MP3 にできるのは再生できる録画（完了・途中まで）だけです: 状態 " + status);
            return 1;
        }

        long started = System.nanoTime();
        return switch (recordingAudioExtractor.createMp3(recording.get())) {
            case CREATED -> {
                String audioPath = recordingRepository.findById(recordingId)
                        .map(Recording::getAudioPath)
                        .orElse(null);
                System.out.printf("MP3 を作りました: %s（処理 %.1f 秒）%n",
                        audioPath, (System.nanoTime() - started) / 1e9);
                yield 0;
            }
            case IN_USE -> {
                System.err.println("この動画のファイルをほかのプロセス（録画・MP3 の作成など）が扱っています。"
                        + "終わってからやり直してください");
                yield 1;
            }
            case FAILED -> {
                System.err.println("MP3 を作れませんでした（理由は上の WARN を見てください）");
                yield 1;
            }
        };
    }
}
