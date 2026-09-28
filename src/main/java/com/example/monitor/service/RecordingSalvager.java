package com.example.monitor.service;

import com.example.monitor.util.DirectorySizeUtils;
import com.example.monitor.util.DiskSpaceUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 途中で終わった録画を、ブラウザで再生できる形に仕立て直す。
 *
 * <h2>なぜ必要か</h2>
 * {@code yt-dlp} は<b>ダウンロードを終えてから最後にまとめて MP4 へ詰め替える</b>。
 * そのため配信の途中で録画が止まると、ディスクには映像そのものは残っているのに
 * <b>ブラウザでは再生できない</b>という状態になる。実機で確認した中身は次のとおり。
 *
 * <table border="1">
 *   <caption>録画の終わり方とディスク上の実体</caption>
 *   <tr><th>状況</th><th>ディスク上のファイル</th><th>{@code <video>} での再生</th></tr>
 *   <tr><td>正常に終了</td><td>本物の MP4</td><td>できる</td></tr>
 *   <tr><td>途中で停止（Twitch）</td><td>拡張子は {@code .mp4} だが<b>中身は MPEG-TS</b></td>
 *       <td><b>できない</b></td></tr>
 *   <tr><td>途中で停止（YouTube）</td><td>映像 {@code .f137.mp4} と音声 {@code .f140.m4a} が別のまま</td>
 *       <td><b>できない</b></td></tr>
 * </table>
 *
 * <p>どちらも <b>{@code ffmpeg} で詰め替える（あるいは結合する）だけで再生できるようになる</b>。
 * 映像・音声は再エンコードせずそのままコピーするので速く、画質も劣化しない
 * （実測: 40分・800MB のファイルで約1.4秒）。
 *
 * <h2>呼び出してよいタイミング</h2>
 * <b>録画プロセスが確実に終わっている場合だけ。</b>書き込み中のファイルを詰め替えると、
 * 出力先を奪って録画中のプロセスを壊す。{@link StreamRecorder} は
 * {@code Process.waitFor()} の後に、{@link RecordingReconciler} は
 * 「アプリが追跡中か」「OS 上にプロセスが残っていないか」の2段階を確認した後に呼んでいる。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingSalvager {

    /**
     * 詰め替えの応答待ち上限（秒）。
     *
     * <p>再エンコードしないため実際は数秒〜数十秒で終わるが、数十GBの録画もありうるので
     * 余裕を持たせている。一方でこの処理は監視の巡回サイクルから呼ばれるため、
     * 無制限にすると監視そのものが止まる。
     */
    private static final long SALVAGE_TIMEOUT_SECONDS = 600;

    /** 詰め替え中の一時ファイルに付ける接尾辞。完成するまで本来の名前にしないために使う。 */
    private static final String WORK_FILE_SUFFIX = ".salvage.mp4";

    /** {@code ffprobe} が MP4 系コンテナに対して返す形式名。 */
    private static final String MP4_FORMAT_MARKER = "mp4";

    private final ExternalCommandRunner externalCommandRunner;

    /**
     * 録画を始めるしきい値（GB）。詰め替えた後にも {@link DiskSpaceUtils#reserveBytes(long)} の空きを残すために使う。
     * 0 以下なら詰め替えの前に空き容量を見ない（{@code monitor.recording.min-free-gb} の「0 で確認しない」に合わせる）。
     *
     * <p>{@code StreamRecorder} の同名のフィールドと同じく final にしない。初期値 0（確認しない）は、
     * Spring を通さずに組み立てるテスト（{@code @InjectMocks}）が実行した機械の空き容量に左右されないようにするため。
     */
    @Value("${monitor.recording.min-free-gb:20}")
    private long minFreeGb = 0;

    /**
     * 録画ファイルが再生できる状態かを確かめ、必要なら再生できる形に直す。
     *
     * @param outputFile 完成予定の録画ファイルのパス（{@code {動画ID}.mp4}）
     * @return 処理の結果。空き容量が足りずに詰め替えを見送った場合は {@link SalvageStatus#INSUFFICIENT_SPACE}
     *         （{@link #remux} 参照）
     */
    public SalvageOutcome ensurePlayable(Path outputFile) {
        if (Files.isRegularFile(outputFile)) {
            if (isPlayableMp4(outputFile)) {
                return sizeOf(outputFile)
                        .map(size -> new SalvageOutcome(SalvageStatus.ALREADY_PLAYABLE, size))
                        .orElseGet(SalvageOutcome::unavailable);
            }
            // 拡張子は .mp4 でも中身が MPEG-TS のことがある（配信途中で止まった場合）
            log.info("録画ファイルがそのままでは再生できない形式のため詰め替えます: file={}", outputFile);
            return remux(List.of(outputFile), outputFile);
        }

        List<Path> parts = findFormatParts(outputFile);
        if (parts.isEmpty()) {
            return SalvageOutcome.unavailable();
        }

        log.info("映像・音声が分かれたまま残っていたため結合します: file={}, parts={}", outputFile, parts.size());
        return remux(parts, outputFile);
    }

    /**
     * ファイルが MP4 コンテナとして読めるかを調べる。
     *
     * <p>拡張子ではなく実際の中身で判断する。<b>配信途中で止まった録画は
     * 拡張子が {@code .mp4} でも中身が MPEG-TS になっている</b>ため、
     * 名前を信じると「再生できないのに完了扱い」になってしまう。
     *
     * @param file 調べるファイル
     * @return MP4 として再生できるなら {@code true}
     */
    private boolean isPlayableMp4(Path file) {
        List<String> command = List.of(
                "ffprobe", "-v", "error",
                "-show_entries", "format=format_name",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.toString());

        return externalCommandRunner.run(command, file, SALVAGE_TIMEOUT_SECONDS)
                .map(output -> output.trim().contains(MP4_FORMAT_MARKER))
                .orElse(false);
    }

    /**
     * 入力を再エンコードせずに MP4 へ詰め替える。
     *
     * <p>一時ファイルに書いてから本来の名前へ移す。入力と出力が同じファイルになる場合
     * （中身だけ MPEG-TS だった場合）に、読みながら上書きして壊すのを避けるため。
     *
     * <p><b>差し替えは、出来上がった一時ファイルが本当に再生できると確かめてから行う。</b>
     * {@code ffmpeg} は入力が途中で壊れていると、<b>終了コード 0 のまま中途半端な出力を
     * 残すことがある</b>。これを検証せずに差し替えてしまうと、単一入力（中身が MPEG-TS
     * だった場合）では<b>元のファイルを壊れた出力で上書きし</b>、複数入力（映像と音声の結合）
     * では<b>結合元の断片を消してしまう</b>。どちらも元データが二度と戻らない。
     * 検証に失敗したときは一時ファイルだけを捨て、元のファイル・断片には一切手を付けない
     * （次の機会に再挑戦できる状態のまま残す）。
     *
     * <p><b>空き容量が「入力の合計＋{@link DiskSpaceUtils#reserveBytes(long)}」に満たなければ始めない。</b>
     * 出力は入力と同じ大きさになるので、足りないまま始めると {@code ffmpeg} は空きを 0 まで使い切ってから失敗し、
     * その間は同じファイルシステムにある H2 とログの書き込みも失敗する。入力の合計ぶんの空きがあっても、
     * 詰め替えで下限を割ると録画中の録画が止まる（{@code StreamRecorder}）ので、下限ぶんも残す。
     * 見送ったときは元のファイル・断片に触らず {@link SalvageStatus#INSUFFICIENT_SPACE} を返す。
     * 空き容量を読めなかったとき・{@link #minFreeGb} が 0 以下のときは今までどおり詰め替える
     * （「判定できなかった」を「足りない」と扱わない）。
     *
     * @param inputs     入力ファイル（1件なら詰め替え、2件以上なら映像と音声の結合）
     * @param outputFile 最終的な出力先
     * @return 処理の結果
     */
    private SalvageOutcome remux(List<Path> inputs, Path outputFile) {
        // 出力は入力と同じ大きさになる。足りないまま始めると ffmpeg が空きを 0 まで使い切ってから失敗し、
        // その間 H2 とログの書き込みも失敗する。元のファイルには触らずに見送る
        if (minFreeGb > 0) {
            long requiredBytes = inputs.stream().mapToLong(DirectorySizeUtils::sizeOf).sum()
                    + DiskSpaceUtils.reserveBytes(minFreeGb);
            DiskSpaceUtils.Capacity disk = DiskSpaceUtils.read(outputFile.toAbsolutePath().getParent());
            if (disk.error() == null && disk.usableBytes() != null && disk.usableBytes() < requiredBytes) {
                log.warn("空き容量が足りないため録画ファイルの詰め替えを見送ります（元のファイルは残します）: file={}, 必要={}MB, 空き={}MB",
                        outputFile, requiredBytes / (1024L * 1024), disk.usableBytes() / (1024L * 1024));
                return SalvageOutcome.insufficientSpace();
            }
        }

        Path workFile = outputFile.resolveSibling(outputFile.getFileName() + WORK_FILE_SUFFIX);

        // 進捗と警告は出させない。出力は使わない（成否は終了コードと出来たファイルで見る）のに、
        // 壊れた入力ではパケットごとに警告が出て、読んで溜める費用だけがかかる
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-y", "-v", "error", "-nostats"));
        for (Path input : inputs) {
            command.add("-i");
            command.add(input.toString());
        }
        command.add("-c");
        command.add("copy");
        // 先頭にインデックスを置き、読み込みながら再生を始められるようにする
        command.add("-movflags");
        command.add("+faststart");
        command.add(workFile.toString());

        if (externalCommandRunner.run(command, outputFile, SALVAGE_TIMEOUT_SECONDS).isEmpty()
                || !Files.isRegularFile(workFile)) {
            log.warn("録画ファイルの詰め替えに失敗しました: file={}", outputFile);
            deleteQuietly(workFile);
            return SalvageOutcome.unavailable();
        }

        if (!isPlayableMp4(workFile)) {
            // ここで気づかずに差し替えると、元のファイル（単一入力の場合）や
            // 結合元の断片（複数入力の場合）を壊れた出力と引き換えに失う
            log.warn("詰め替えた結果が再生できる MP4 になっていなかったため、元のファイルはそのまま残します: file={}",
                    outputFile);
            deleteQuietly(workFile);
            return SalvageOutcome.unavailable();
        }

        try {
            Files.move(workFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("詰め替えたファイルの差し替えに失敗しました: file={}", outputFile, e);
            deleteQuietly(workFile);
            return SalvageOutcome.unavailable();
        }

        // 結合元の断片はもう不要（同じ内容を二重に持つとディスクを無駄に食う）
        inputs.stream().filter(input -> !input.equals(outputFile)).forEach(this::deleteQuietly);

        return sizeOf(outputFile)
                .map(size -> {
                    log.info("途中までの録画を再生できる形に直しました: file={}, size={}", outputFile, size);
                    return new SalvageOutcome(SalvageStatus.SALVAGED, size);
                })
                .orElseGet(SalvageOutcome::unavailable);
    }

    /**
     * 映像・音声が別ファイルのまま残っているものを探す。
     *
     * <p>{@code yt-dlp} は結合前のファイルを {@code {動画ID}.f{形式ID}.{拡張子}} という名前で置く。
     * 結合が済む前に止まるとこれらだけが残る。
     *
     * <p>結合の入力順は<b>映像を先にする</b>。{@code ffmpeg} は入力順にストリームを並べるため、
     * 音声が先だと再生機によっては映像が出ないことがある。
     *
     * @param outputFile 完成予定の録画ファイルのパス
     * @return 見つかった断片。無ければ空リスト
     */
    private List<Path> findFormatParts(Path outputFile) {
        String fileName = outputFile.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        Path directory = outputFile.getParent();

        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> isFormatPart(path.getFileName().toString(), baseName))
                    // 映像（.mp4 / .webm）を音声（.m4a / .opus）より前に置く
                    .sorted(Comparator.comparing((Path path) -> isAudioOnly(path.getFileName().toString()))
                            .thenComparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            log.warn("録画ファイルの断片を探せませんでした: directory={}", directory, e);
            return List.of();
        }
    }

    /**
     * ファイル名が「結合前の映像・音声ファイル」かどうかを判定する。
     *
     * <p>ダウンロード途中の部分ファイル（{@code ...-Frag1771} のような名前）は除く。
     * これらは単体では読めないため、結合の入力にすると {@code ffmpeg} が失敗する。
     *
     * @param fileName 調べるファイル名
     * @param baseName 動画 ID（拡張子を除いた録画ファイル名）
     * @return 結合の入力にしてよいなら {@code true}
     */
    private boolean isFormatPart(String fileName, String baseName) {
        if (!fileName.startsWith(baseName + ".f")) {
            return false;
        }
        return fileName.endsWith(".mp4") || fileName.endsWith(".m4a")
                || fileName.endsWith(".webm") || fileName.endsWith(".opus");
    }

    /**
     * 音声だけのファイルかどうかを拡張子で判定する。結合時の並び順に使う。
     *
     * @param fileName 調べるファイル名
     * @return 音声だけなら {@code true}
     */
    private boolean isAudioOnly(String fileName) {
        return fileName.endsWith(".m4a") || fileName.endsWith(".opus");
    }

    /**
     * ファイルサイズを取得する。
     *
     * @param file 対象ファイル
     * @return サイズ（バイト）。取得できなければ {@link Optional#empty()}
     */
    private Optional<Long> sizeOf(Path file) {
        try {
            return Optional.of(Files.size(file));
        } catch (IOException e) {
            log.warn("録画ファイルのサイズ取得に失敗しました: {}", file, e);
            return Optional.empty();
        }
    }

    /**
     * 後始末用の削除。消せなくても処理は続ける。
     *
     * @param file 削除するファイル
     */
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("作業ファイルを削除できませんでした: {}", file, e);
        }
    }

    /** 録画ファイルをどう扱えたか。 */
    public enum SalvageStatus {
        /** 最初から再生できる状態だった（正常に完了した録画）。 */
        ALREADY_PLAYABLE,
        /** 途中までの内容を再生できる形に直した。 */
        SALVAGED,
        /** 再生できるファイルを用意できなかった。 */
        UNAVAILABLE,
        /**
         * 空き容量が足りないため詰め替えを見送った。元のファイル・断片はそのまま残してあり、
         * 空きができれば直せる（{@link RecordingReconciler} は失敗として覚えず、後始末のたびに試し直す）。
         */
        INSUFFICIENT_SPACE
    }

    /**
     * {@link #ensurePlayable} の結果。
     *
     * @param status        どう扱えたか
     * @param fileSizeBytes 再生できるファイルのサイズ。再生できない場合（{@link SalvageStatus#UNAVAILABLE}・
     *                      {@link SalvageStatus#INSUFFICIENT_SPACE}）は {@code null}
     */
    public record SalvageOutcome(SalvageStatus status, Long fileSizeBytes) {

        /**
         * 再生できるファイルを用意できなかった結果を組み立てる。
         *
         * @return 結果
         */
        public static SalvageOutcome unavailable() {
            return new SalvageOutcome(SalvageStatus.UNAVAILABLE, null);
        }

        /**
         * 空き容量が足りないため詰め替えを見送った結果を組み立てる。
         *
         * @return 結果
         */
        public static SalvageOutcome insufficientSpace() {
            return new SalvageOutcome(SalvageStatus.INSUFFICIENT_SPACE, null);
        }

        /**
         * 再生できるファイルが用意できたかどうか。
         *
         * @return 再生できるなら {@code true}
         */
        public boolean isPlayable() {
            return status == SalvageStatus.ALREADY_PLAYABLE || status == SalvageStatus.SALVAGED;
        }
    }
}
