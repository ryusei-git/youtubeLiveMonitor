package com.example.monitor.service;

import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingSalvager")
// ArgumentCaptor.forClass(List.class) は総称型の情報を渡せず未検査キャストになる（StreamRecorderTest と同じ）
@SuppressWarnings("unchecked")
class RecordingSalvagerTest {

    /** 詰め替え中の作業ファイルに付く接尾辞（{@code RecordingSalvager} の定数と対になっている）。 */
    private static final String WORK_FILE_SUFFIX = ".salvage.mp4";

    /** {@code ffprobe} が MP4 コンテナに対して返す形式名。 */
    private static final String MP4_FORMAT = "mov,mp4,m4a,3gp,3g2,mj2";

    @Mock
    private ExternalCommandRunner externalCommandRunner;

    @InjectMocks
    private RecordingSalvager recordingSalvager;

    /**
     * {@code ffprobe} が、詰め替え前のファイルに対してコンテナ形式を返す状態を模す。
     *
     * @param formatName 返させる形式名
     */
    private void givenProbeReturns(String formatName) {
        when(externalCommandRunner.run(argThatProbesSourceFile(), any(Path.class), anyLong()))
                .thenReturn(Optional.of(formatName + "\n"));
    }

    /**
     * 指定したコマンド名で始まる引数にだけ一致するマッチャ。
     *
     * @param commandName コマンド名
     * @return マッチャ
     */
    private List<String> argThatStartsWith(String commandName) {
        return argThat(
                command -> command != null && !command.isEmpty() && command.get(0).equals(commandName));
    }

    /**
     * 詰め替え<b>前</b>のファイルに対する {@code ffprobe} だけに一致するマッチャ。
     *
     * <p>詰め替えの結果が再生できるかの確認でも {@code ffprobe} が動くため、
     * コマンド名だけで一致させると両方に同じ形式を返してしまい、
     * 「元は壊れていたが詰め替えは成功した」という肝心の状況を作れない。
     *
     * @return マッチャ
     */
    private List<String> argThatProbesSourceFile() {
        return argThat(command -> isProbe(command) && !probedFile(command).endsWith(WORK_FILE_SUFFIX));
    }

    /**
     * 詰め替え<b>後</b>の作業ファイルに対する {@code ffprobe} だけに一致するマッチャ。
     *
     * @return マッチャ
     */
    private List<String> argThatProbesWorkFile() {
        return argThat(command -> isProbe(command) && probedFile(command).endsWith(WORK_FILE_SUFFIX));
    }

    /**
     * コマンドが {@code ffprobe} の起動かどうかを判定する。
     *
     * @param command 起動されたコマンド
     * @return {@code ffprobe} なら {@code true}
     */
    private static boolean isProbe(List<String> command) {
        return command != null && !command.isEmpty() && command.get(0).equals("ffprobe");
    }

    /**
     * {@code ffprobe} が対象にしているファイル（コマンドの最後の引数）を取り出す。
     *
     * @param command 起動されたコマンド
     * @return 対象ファイルのパス文字列
     */
    private static String probedFile(List<String> command) {
        return command.get(command.size() - 1);
    }

    /**
     * {@code ffmpeg} が成功し、そのまま再生できる作業ファイルを作る状態を模す。
     *
     * @param contents 作業ファイルに書き込む内容
     */
    private void givenFfmpegCreatesWorkFile(String contents) {
        givenFfmpegCreatesWorkFile(contents, MP4_FORMAT);
    }

    /**
     * {@code ffmpeg} が成功し、指定した形式の作業ファイルを作る状態を模す。
     *
     * <p>{@code ffmpeg} は入力が壊れていると終了コード 0 のまま再生できない出力を残すことがある。
     * その状況を作れるよう、作業ファイルの中身の形式を指定できるようにしている。
     *
     * @param contents       作業ファイルに書き込む内容
     * @param workFileFormat 作業ファイルの形式として {@code ffprobe} に返させる形式名
     */
    private void givenFfmpegCreatesWorkFile(String contents, String workFileFormat) {
        when(externalCommandRunner.run(argThatStartsWith("ffmpeg"), any(Path.class), anyLong()))
                .thenAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    Files.writeString(Path.of(command.get(command.size() - 1)), contents);
                    return Optional.of("");
                });
        when(externalCommandRunner.run(argThatProbesWorkFile(), any(Path.class), anyLong()))
                .thenReturn(Optional.of(workFileFormat + "\n"));
    }

    /**
     * 起動されたコマンドのうち {@code ffmpeg} のものを取り出す。
     *
     * <p>詰め替えの前後で {@code ffprobe} も起動されるため、素朴に捕捉すると混ざる。
     *
     * @return {@code ffmpeg} の起動コマンド
     */
    private List<String> capturedFfmpegCommand() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(externalCommandRunner, atLeastOnce()).run(captor.capture(), any(Path.class), anyLong());
        return captor.getAllValues().stream()
                .filter(command -> command.get(0).equals("ffmpeg"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ffmpeg が起動されていません"));
    }

    @Nested
    @DisplayName("ensurePlayable()")
    class EnsurePlayable {

        @Test
        @DisplayName("正常系：最初から再生できるMP4なら何もしない")
        void testMethod01(@TempDir Path tempDir) throws IOException {
            Path outputFile = tempDir.resolve("video001.mp4");
            Files.writeString(outputFile, "already mp4");
            givenProbeReturns(MP4_FORMAT);

            SalvageOutcome outcome = recordingSalvager.ensurePlayable(outputFile);

            assertThat(outcome.status()).isEqualTo(SalvageStatus.ALREADY_PLAYABLE);
            assertThat(outcome.fileSizeBytes()).isEqualTo(Files.size(outputFile));
        }

        @Test
        @DisplayName("正常系：中身がMPEG-TSなら詰め替えて「途中まで」として返す")
        void testMethod02(@TempDir Path tempDir) throws IOException {
            // 配信途中で止まった録画は拡張子が .mp4 でも中身が MPEG-TS になっており、
            // そのままではブラウザで再生できない（実機で確認）
            Path outputFile = tempDir.resolve("video001.mp4");
            Files.writeString(outputFile, "raw mpegts payload");
            givenProbeReturns("mpegts");
            givenFfmpegCreatesWorkFile("remuxed mp4 payload");

            SalvageOutcome outcome = recordingSalvager.ensurePlayable(outputFile);

            assertThat(outcome.status()).isEqualTo(SalvageStatus.SALVAGED);
            assertThat(Files.readString(outputFile)).isEqualTo("remuxed mp4 payload");
            assertThat(outcome.fileSizeBytes()).isEqualTo(Files.size(outputFile));
        }

        @Test
        @DisplayName("正常系：詰め替えは再エンコードせずストリームコピーで行う")
        void testMethod03(@TempDir Path tempDir) throws IOException {
            // 再エンコードすると画質が落ちるうえ、長時間の録画では現実的な時間で終わらない
            Path outputFile = tempDir.resolve("video001.mp4");
            Files.writeString(outputFile, "raw mpegts payload");
            givenProbeReturns("mpegts");
            givenFfmpegCreatesWorkFile("remuxed");

            recordingSalvager.ensurePlayable(outputFile);

            assertThat(capturedFfmpegCommand()).containsSequence("-c", "copy");
        }

        @Test
        @DisplayName("正常系：映像と音声が別ファイルのまま残っていれば結合する")
        void testMethod04(@TempDir Path tempDir) throws IOException {
            // YouTube は映像と音声を別々に落としてから結合するため、途中で止まると両方が残る
            Files.writeString(tempDir.resolve("video001.f137.mp4"), "video stream");
            Files.writeString(tempDir.resolve("video001.f140.m4a"), "audio stream");
            Path outputFile = tempDir.resolve("video001.mp4");
            givenFfmpegCreatesWorkFile("merged mp4");

            SalvageOutcome outcome = recordingSalvager.ensurePlayable(outputFile);

            assertThat(outcome.status()).isEqualTo(SalvageStatus.SALVAGED);
            assertThat(Files.readString(outputFile)).isEqualTo("merged mp4");
        }

        @Test
        @DisplayName("正常系：結合の入力は映像を音声より先に並べる")
        void testMethod05(@TempDir Path tempDir) throws IOException {
            // ffmpeg は入力順にストリームを並べるため、音声が先だと再生機によっては映像が出ない
            Files.writeString(tempDir.resolve("video001.f140.m4a"), "audio stream");
            Files.writeString(tempDir.resolve("video001.f137.mp4"), "video stream");
            Path outputFile = tempDir.resolve("video001.mp4");
            givenFfmpegCreatesWorkFile("merged");

            recordingSalvager.ensurePlayable(outputFile);

            List<String> command = capturedFfmpegCommand();
            assertThat(command.indexOf(tempDir.resolve("video001.f137.mp4").toString()))
                    .isLessThan(command.indexOf(tempDir.resolve("video001.f140.m4a").toString()));
        }

        @Test
        @DisplayName("正常系：結合が済んだら元の断片を消す")
        void testMethod06(@TempDir Path tempDir) throws IOException {
            // 同じ内容を二重に持つとディスクを無駄に使う
            Path videoPart = tempDir.resolve("video001.f137.mp4");
            Path audioPart = tempDir.resolve("video001.f140.m4a");
            Files.writeString(videoPart, "video stream");
            Files.writeString(audioPart, "audio stream");
            givenFfmpegCreatesWorkFile("merged");

            recordingSalvager.ensurePlayable(tempDir.resolve("video001.mp4"));

            assertThat(videoPart).doesNotExist();
            assertThat(audioPart).doesNotExist();
        }

        @Test
        @DisplayName("正常系：ダウンロード途中の部分ファイルは結合の入力にしない")
        void testMethod07(@TempDir Path tempDir) throws IOException {
            // -Frag1771 のような部分ファイルは単体では読めず、入力にすると ffmpeg が失敗する
            Files.writeString(tempDir.resolve("video001.f137.mp4"), "video stream");
            Files.writeString(tempDir.resolve("video001.f399.mp4-Frag1771"), "partial fragment");
            givenFfmpegCreatesWorkFile("merged");

            recordingSalvager.ensurePlayable(tempDir.resolve("video001.mp4"));

            assertThat(capturedFfmpegCommand()).noneMatch(arg -> arg.contains("Frag1771"));
        }

        @Test
        @DisplayName("異常系：ファイルも断片も無ければ何も起動せず「用意できなかった」を返す")
        void testMethod08(@TempDir Path tempDir) {
            SalvageOutcome outcome = recordingSalvager.ensurePlayable(tempDir.resolve("video001.mp4"));

            assertThat(outcome.status()).isEqualTo(SalvageStatus.UNAVAILABLE);
            assertThat(outcome.isPlayable()).isFalse();
            verifyNoInteractions(externalCommandRunner);
        }

        @Test
        @DisplayName("異常系：詰め替えに失敗した場合は元のファイルを壊さない")
        void testMethod09(@TempDir Path tempDir) throws IOException {
            // 失敗しても元が残っていれば、次の巡回で再挑戦できる
            Path outputFile = tempDir.resolve("video001.mp4");
            Files.writeString(outputFile, "raw mpegts payload");
            givenProbeReturns("mpegts");
            when(externalCommandRunner.run(argThatStartsWith("ffmpeg"), any(Path.class), anyLong()))
                    .thenReturn(Optional.empty());

            SalvageOutcome outcome = recordingSalvager.ensurePlayable(outputFile);

            assertThat(outcome.status()).isEqualTo(SalvageStatus.UNAVAILABLE);
            assertThat(Files.readString(outputFile)).isEqualTo("raw mpegts payload");
        }

        @Test
        @DisplayName("異常系：ffmpegが成功と言っても出力が無ければ用意できなかったとする")
        void testMethod10(@TempDir Path tempDir) throws IOException {
            Path outputFile = tempDir.resolve("video001.mp4");
            Files.writeString(outputFile, "raw mpegts payload");
            givenProbeReturns("mpegts");
            when(externalCommandRunner.run(argThatStartsWith("ffmpeg"), any(Path.class), anyLong()))
                    .thenReturn(Optional.of(""));

            assertThat(recordingSalvager.ensurePlayable(outputFile).status())
                    .isEqualTo(SalvageStatus.UNAVAILABLE);
        }

        @Test
        @DisplayName("異常系：形式を判定できない場合は詰め替えを試みる")
        void testMethod11(@TempDir Path tempDir) throws IOException {
            // ffprobe が動かない環境でも、詰め替えが通れば再生できるようになる可能性がある
            Path outputFile = tempDir.resolve("video001.mp4");
            Files.writeString(outputFile, "unknown payload");
            when(externalCommandRunner.run(argThatProbesSourceFile(), any(Path.class), anyLong()))
                    .thenReturn(Optional.empty());
            givenFfmpegCreatesWorkFile("remuxed");

            assertThat(recordingSalvager.ensurePlayable(outputFile).status())
                    .isEqualTo(SalvageStatus.SALVAGED);
        }

        @Test
        @DisplayName("正常系：詰め替えた出力が再生できることを確かめてから断片を消す")
        void testMethod12(@TempDir Path tempDir) throws IOException {
            Path videoPart = tempDir.resolve("video001.f137.mp4");
            Path audioPart = tempDir.resolve("video001.f140.m4a");
            Files.writeString(videoPart, "video stream");
            Files.writeString(audioPart, "audio stream");
            Path outputFile = tempDir.resolve("video001.mp4");
            givenFfmpegCreatesWorkFile("merged mp4", MP4_FORMAT);

            SalvageOutcome outcome = recordingSalvager.ensurePlayable(outputFile);

            assertThat(outcome.status()).isEqualTo(SalvageStatus.SALVAGED);
            assertThat(Files.readString(outputFile)).isEqualTo("merged mp4");
            assertThat(videoPart).doesNotExist();
            assertThat(audioPart).doesNotExist();
            // 断片を消す前に、出来上がったものが本当に再生できるかを確かめている
            verify(externalCommandRunner).run(argThatProbesWorkFile(), any(Path.class), anyLong());
        }

        @Test
        @DisplayName("異常系：詰め替えた出力が再生できない場合は結合元の断片を消さない")
        void testMethod13(@TempDir Path tempDir) throws IOException {
            // ffmpeg は入力が途中で壊れていると、終了コード 0 のまま中途半端な出力を残すことがある。
            // これを検証せずに採用すると、元データを消したうえで再生できないファイルだけが残る
            Path videoPart = tempDir.resolve("video001.f137.mp4");
            Path audioPart = tempDir.resolve("video001.f140.m4a");
            Files.writeString(videoPart, "video stream");
            Files.writeString(audioPart, "audio stream");
            Path outputFile = tempDir.resolve("video001.mp4");
            givenFfmpegCreatesWorkFile("broken output", "mpegts");

            SalvageOutcome outcome = recordingSalvager.ensurePlayable(outputFile);

            assertThat(outcome.status()).isEqualTo(SalvageStatus.UNAVAILABLE);
            assertThat(videoPart).hasContent("video stream");
            assertThat(audioPart).hasContent("audio stream");
            assertThat(outputFile).doesNotExist();
            assertThat(tempDir.resolve("video001.mp4" + WORK_FILE_SUFFIX)).doesNotExist();
        }

        @Test
        @DisplayName("異常系：詰め替えた出力が再生できない場合は元のファイルを上書きしない")
        void testMethod14(@TempDir Path tempDir) throws IOException {
            // 単一入力（中身だけ MPEG-TS だった場合）では入力と出力が同じファイルになるため、
            // 検証せずに差し替えると元の録画そのものを壊れた出力で潰してしまう
            Path outputFile = tempDir.resolve("video001.mp4");
            Files.writeString(outputFile, "raw mpegts payload");
            givenProbeReturns("mpegts");
            givenFfmpegCreatesWorkFile("broken output", "mpegts");

            SalvageOutcome outcome = recordingSalvager.ensurePlayable(outputFile);

            assertThat(outcome.status()).isEqualTo(SalvageStatus.UNAVAILABLE);
            assertThat(Files.readString(outputFile)).isEqualTo("raw mpegts payload");
            assertThat(tempDir.resolve("video001.mp4" + WORK_FILE_SUFFIX)).doesNotExist();
        }
    }
}
