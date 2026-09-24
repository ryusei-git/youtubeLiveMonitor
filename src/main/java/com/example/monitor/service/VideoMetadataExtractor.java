package com.example.monitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 録画ファイルから再生時間とサムネイル画像を取り出す。
 *
 * <p>どちらも外部コマンド（{@code ffprobe} / {@code ffmpeg}）に任せている。
 * これらは録画時の映像・音声のマージに既に使っているため、追加のインストールは要らない。
 *
 * <p><b>取得できなくても録画そのものは成功として扱う。</b>
 * サムネイルや再生時間は一覧を見やすくするための付加情報でしかなく、
 * これが取れないせいで再生できる録画を失敗扱いにするのは本末転倒なため。
 * 失敗時は {@link Optional#empty()} を返し、呼び出し側は未設定のまま進む。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoMetadataExtractor {

    /** サムネイルの拡張子。{@code <img>} でそのまま表示できる形式にする。 */
    private static final String THUMBNAIL_EXTENSION = ".jpg";

    /**
     * サムネイルを切り出す位置（再生時間に対する割合）。
     *
     * <p>冒頭は待機画面や暗転であることが多く、0 秒地点だと真っ黒な画像になりやすい。
     * かといって中盤すぎると長時間配信では切り出しに時間がかかるため、少し進んだ位置にしている。
     */
    private static final double THUMBNAIL_POSITION_RATIO = 0.1;

    /**
     * 外部コマンドの応答待ち上限（秒）。巡回処理を巻き込んで止めないために設ける。
     *
     * <p>サムネイルと再生時間は「無くても録画は再生できる」付加情報なので、
     * 短めに切り上げて次の巡回に任せてよい。
     */
    private static final long COMMAND_TIMEOUT_SECONDS = 30;

    private final ExternalCommandRunner externalCommandRunner;

    /**
     * 録画ファイルの再生時間（秒）を読み取る。
     *
     * @param videoFile 対象の録画ファイル
     * @return 再生時間（秒）。読み取れなかった場合は {@link Optional#empty()}
     */
    public Optional<Integer> extractDurationSeconds(Path videoFile) {
        if (!Files.isRegularFile(videoFile)) {
            return Optional.empty();
        }

        List<String> command = List.of(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                videoFile.toString());

        return externalCommandRunner.run(command, videoFile, COMMAND_TIMEOUT_SECONDS)
                .flatMap(this::parseDurationSeconds);
    }

    /**
     * 映像ストリームだけの長さ（秒）を読み取る。
     *
     * <p><b>コンテナ全体の長さ（{@link #extractDurationSeconds}）とは一致しないことがある。</b>
     * 途中で終わった録画を {@link RecordingSalvager} が詰め替えると、
     * 映像と音声で取得できた量が食い違ったまま 1 つのファイルになる。
     * 実際に、コンテナは 3738 秒と申告するのに<b>映像は 311 秒しか入っていない</b>録画があった
     * （音声だけが最後まで取得できていた）。
     *
     * <p>サムネイルの切り出し位置をコンテナの長さから決めると、この場合
     * <b>映像が存在しない位置を指してしまい 1 枚も切り出せない</b>。
     *
     * @param videoFile 対象の録画ファイル
     * @return 映像の長さ（秒）。読み取れなかった場合は {@link Optional#empty()}
     */
    public Optional<Integer> extractVideoStreamDurationSeconds(Path videoFile) {
        if (!Files.isRegularFile(videoFile)) {
            return Optional.empty();
        }

        List<String> command = List.of(
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                videoFile.toString());

        return externalCommandRunner.run(command, videoFile, COMMAND_TIMEOUT_SECONDS)
                .flatMap(this::parseDurationSeconds);
    }

    /**
     * 録画ファイルからサムネイル画像を切り出す。
     *
     * <p>既に同名の画像があれば作り直さずそのまま使う（巡回のたびに作り直さないため）。
     *
     * @param videoFile       対象の録画ファイル
     * @param durationSeconds 録画の再生時間（秒）。切り出し位置の決定に使う
     * @return 生成された（または既に存在した）サムネイル画像のパス。
     *         生成できなかった場合は {@link Optional#empty()}
     */
    public Optional<Path> extractThumbnail(Path videoFile, int durationSeconds) {
        if (!Files.isRegularFile(videoFile)) {
            return Optional.empty();
        }

        Path thumbnail = thumbnailPathFor(videoFile);
        if (Files.isRegularFile(thumbnail)) {
            return Optional.of(thumbnail);
        }

        long positionSeconds = positionFor(durationSeconds);
        if (captureFrameAt(videoFile, thumbnail, positionSeconds)) {
            return Optional.of(thumbnail);
        }
        if (positionSeconds == 0) {
            return Optional.empty();
        }

        // ここから先は失敗したときだけ通る。映像の長さを調べるのに ffprobe をもう一度
        // 起動するので、うまくいった録画にその費用を払わせないよう、あえて後回しにしている
        long videoPosition = extractVideoStreamDurationSeconds(videoFile)
                .filter(videoDuration -> videoDuration > 0)
                .map(videoDuration -> positionFor(Math.min(videoDuration, durationSeconds)))
                .orElse(0L);

        if (videoPosition > 0 && videoPosition != positionSeconds
                && captureFrameAt(videoFile, thumbnail, videoPosition)) {
            log.info("コンテナの長さでは映像の無い位置だったため、映像の長さから切り出し直しました: "
                    + "file={}, {}秒→{}秒", videoFile, positionSeconds, videoPosition);
            return Optional.of(thumbnail);
        }

        // それでも切り出せない場合は先頭で試す。冒頭は暗転しがちなので普段は避けているが、
        // 真っ黒でも「画像が無い」よりは録画一覧で見分けが付く
        if (captureFrameAt(videoFile, thumbnail, 0)) {
            log.info("指定位置から切り出せなかったため、先頭のフレームを使いました: file={}", videoFile);
            return Optional.of(thumbnail);
        }
        return Optional.empty();
    }

    /**
     * 再生時間から切り出し位置（秒）を決める。
     *
     * @param durationSeconds 基準にする長さ（秒）
     * @return 切り出し位置（秒）
     */
    private static long positionFor(int durationSeconds) {
        return Math.max(0, (long) (durationSeconds * THUMBNAIL_POSITION_RATIO));
    }

    /**
     * 指定位置のフレームを 1 枚切り出す。
     *
     * @param videoFile       対象の録画ファイル
     * @param thumbnail       出力先
     * @param positionSeconds 切り出す位置（秒）
     * @return 画像を作れたら {@code true}
     */
    private boolean captureFrameAt(Path videoFile, Path thumbnail, long positionSeconds) {
        List<String> command = List.of(
                "ffmpeg", "-y",
                // 進捗と警告は出させない。出力は使わない（成否は下のとおり画像の有無で見る）のに、
                // 壊れた入力ではパケットごとに警告が出て、読んで溜める費用だけがかかる
                "-v", "error", "-nostats",
                // -ss を入力より前に置くとキーフレーム単位で高速にシークできる
                "-ss", String.valueOf(positionSeconds),
                "-i", videoFile.toString(),
                "-frames:v", "1",
                "-vf", "scale=320:-1",
                thumbnail.toString());

        // ffmpeg は 1 枚も切り出せなくても終了コード 0 を返すことがあるため、
        // 成否はファイルが実際に出来たかどうかで判断する（録画の成否判定と同じ考え方）
        externalCommandRunner.run(command, videoFile, COMMAND_TIMEOUT_SECONDS);
        return Files.isRegularFile(thumbnail);
    }

    /**
     * 録画ファイルに対応するサムネイル画像のパスを決める。
     *
     * <p>録画ファイルと同じディレクトリに、拡張子だけ変えた名前で置く。
     * こうしておくと録画ファイルを消すときに一緒に消しやすく、
     * 静的配信（{@code /recordings/**}）にもそのまま乗る。
     *
     * @param videoFile 対象の録画ファイル
     * @return サムネイル画像のパス
     */
    public Path thumbnailPathFor(Path videoFile) {
        String fileName = videoFile.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
        return videoFile.resolveSibling(baseName + THUMBNAIL_EXTENSION);
    }

    /**
     * {@code ffprobe} が出力する秒数（小数）を整数秒に変換する。
     *
     * @param rawOutput {@code ffprobe} の標準出力
     * @return 秒数。数値として解釈できなければ {@link Optional#empty()}
     */
    private Optional<Integer> parseDurationSeconds(String rawOutput) {
        try {
            return Optional.of((int) Double.parseDouble(rawOutput.trim()));
        } catch (NumberFormatException e) {
            log.warn("再生時間を解釈できませんでした: output={}", rawOutput.trim());
            return Optional.empty();
        }
    }
}
