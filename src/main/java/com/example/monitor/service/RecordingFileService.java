package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.dto.DiskUsageResponse.ChannelDiskUsage;
import com.example.monitor.dto.OrphanedCleanupResponse;
import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code monitor.recording.directory} 配下の録画ファイルそのものを扱う。
 *
 * <p>{@link RecordingHistoryService} が DB 上の履歴を扱うのに対し、こちらはファイルシステム上の
 * 実体を扱う。ディスク使用量の集計は DB の {@code fileSizeBytes} の合計ではなく実ファイルを
 * 走査して求める。録画失敗時に断片ファイル（{@code {videoId}.f137.mp4} 等）がディスクに
 * 残ったまま DB 上は完成ファイルの記録が無い、というケース（実際に発生した）も
 * 取りこぼさずに容量へ反映するため。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingFileService {

    /** 削除済みチャンネルのディレクトリに表示する仮の名前。 */
    private static final String UNKNOWN_CHANNEL_NAME = "(削除済みチャンネル)";

    private final MonitorProperties monitorProperties;
    private final MonitoredChannelRepository monitoredChannelRepository;
    private final RecordingRepository recordingRepository;
    private final ProcessLauncher processLauncher;

    /**
     * 録画ファイルを削除する。
     *
     * <p>{@code {動画ID}} を先頭に持つファイルをすべて削除する（マージ済みの動画ファイル・
     * サムネイルに加えて、マージが完了しなかった場合の断片ファイルも含む）。
     * 以前は {@code filePath}（マージ後の完成ファイル1つ）だけを消していたが、
     * 録画が失敗してマージ前に中断された場合はその完成ファイル自体が存在しないため、
     * {@code yt-dlp} が残した断片ファイル（{@code {動画ID}.f137.mp4} 等）が消えずに残った
     * （実際に発生した：アプリの再起動を挟んで録画が中断され、失敗として補正・削除された後も、
     * 断片ファイルだけが登録中チャンネルのディレクトリに計 5.8GB 残り続けた）。
     *
     * <p>失敗してもログに残すだけで例外は投げない。呼び出し元（{@link RecordingHistoryService#deleteRecording}）
     * が DB からの削除を先に済ませている前提で、ファイル削除はそれに追随する後始末という位置づけのため
     * （{@code ChannelLogReader.deleteChannelLogs} と同じ考え方）。
     *
     * @param recording 削除対象の録画履歴
     */
    public void deleteFile(Recording recording) {
        Path directory = resolveFilePath(recording).getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            log.warn("削除対象の録画ファイルが見つかりませんでした: video={}", recording.getVideoId());
            return;
        }

        List<Path> matchingFiles;
        try (Stream<Path> files = Files.list(directory)) {
            matchingFiles = files
                    .filter(Files::isRegularFile)
                    .filter(file -> extractVideoId(file).equals(recording.getVideoId()))
                    .toList();
        } catch (IOException e) {
            log.error("録画ファイルの削除に失敗しました: {}", directory, e);
            return;
        }

        int removed = deleteFiles(matchingFiles);
        if (removed == 0) {
            log.warn("削除対象の録画ファイルが見つかりませんでした: video={}", recording.getVideoId());
        }
    }

    /**
     * ファイル名から動画IDを取り出す。
     *
     * <p>録画関連のファイルはすべて {@code {動画ID}.拡張子...}（{@code yt-dlp} の出力テンプレート、
     * サムネイルとも共通）という命名なので、最初の {@code .} より前を動画IDとみなせる。
     * YouTube の動画IDの文字集合に {@code .} は含まれないため、この切り出しは安全に成立する。
     *
     * @param file 対象ファイル
     * @return 動画ID相当の文字列
     */
    private static String extractVideoId(Path file) {
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.indexOf('.');
        return dotIndex < 0 ? fileName : fileName.substring(0, dotIndex);
    }

    /**
     * 複数のファイルを削除する。
     *
     * <p>個々の削除に失敗しても中断せず、消せたものだけを数える
     * （{@link #deleteDirectoryRecursively}と同じ考え方）。
     *
     * @param files 削除対象のファイル
     * @return 削除できた件数
     */
    private int deleteFiles(List<Path> files) {
        int removed = 0;
        for (Path file : files) {
            try {
                if (Files.deleteIfExists(file)) {
                    removed++;
                }
            } catch (IOException e) {
                log.warn("録画ファイルの削除に失敗しました: {}", file, e);
            }
        }
        return removed;
    }

    /**
     * 録画ファイルが実際に存在するか確認し、存在すればサイズを返す。
     *
     * @param recording 確認対象の録画履歴
     * @return ファイルが存在すればそのサイズ（バイト）、存在しなければ {@link Optional#empty()}
     */
    public Optional<Long> sizeIfExists(Recording recording) {
        return sizeIfExists(resolveFilePath(recording));
    }

    /**
     * 指定したファイルが実際に存在するか確認し、存在すればサイズを返す。
     *
     * <p><b>録画が成功したかどうかの唯一の判断材料。</b>
     * {@link StreamRecorder} が録画終了時に、{@link RecordingReconciler} が置き去りの
     * 録画履歴を補正するときに、どちらもこの同じ基準で完了・失敗を決める。
     * {@code yt-dlp} の終了コードを基準にしてはならない（{@code StreamRecorder} の
     * クラス JavaDoc 参照）。
     *
     * @param file 確認対象のファイル
     * @return ファイルが存在すればそのサイズ（バイト）、存在しなければ {@link Optional#empty()}
     */
    public Optional<Long> sizeIfExists(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.size(file));
        } catch (IOException e) {
            log.warn("録画ファイルのサイズ取得に失敗しました: {}", file, e);
            return Optional.empty();
        }
    }

    /**
     * 録画ファイルが実在する場合にそのパスを返す。
     *
     * <p>サムネイル生成のように「実ファイルを直接操作したい」処理向け。
     *
     * @param recording 対象の録画履歴
     * @return 実在する録画ファイルのパス。存在しなければ {@link Optional#empty()}
     */
    public Optional<Path> resolveExistingFile(Recording recording) {
        Path file = resolveFilePath(recording);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /**
     * 絶対パスを、DB に保存する形（録画ディレクトリからの相対パス）に戻す。
     *
     * <p>DB には相対パスだけを保存している。保存先ディレクトリを変えても
     * 既存の記録がそのまま使えるようにするため。
     *
     * @param file 録画ディレクトリ配下のファイル
     * @return 録画ディレクトリからの相対パス（区切り文字は {@code /}）
     */
    public String toRelativePath(Path file) {
        Path baseDirectory = Path.of(monitorProperties.recording().directory());
        // 画面では URL の一部として使うため、OS 依存の区切り文字ではなく "/" に揃える
        return baseDirectory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }

    /**
     * 録画履歴の {@code filePath}（相対パス）を、実際のファイルシステム上の絶対パスに解決する。
     *
     * @param recording 対象の録画履歴
     * @return 解決済みのパス
     */
    public Path resolveFilePath(Recording recording) {
        return Path.of(monitorProperties.recording().directory()).resolve(recording.getFilePath());
    }

    /**
     * この録画に関係するファイルがディスクに 1 つでも残っているかを調べる。
     *
     * <p>完成ファイルだけでなく、結合前の映像・音声ファイル（{@code {動画ID}.f137.mp4} 等）も数える。
     * <b>{@link RecordingReconciler} が「詰め替えを試みる価値があるか」を、
     * 外部コマンドを起動する前に安く判断するために使う</b>
     * （中身が何も残っていない録画を毎巡回 {@code ffprobe} に掛けないため）。
     *
     * @param recording 対象の録画履歴
     * @return 関係するファイルが 1 つでもあれば {@code true}
     */
    public boolean hasAnyFileFor(Recording recording) {
        Path file = resolveFilePath(recording);
        Path directory = file.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }

        String videoId = extractVideoId(file);
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(candidate -> videoId.equals(extractVideoId(candidate)));
        } catch (IOException e) {
            log.warn("録画ファイルの有無を確認できませんでした: directory={}", directory, e);
            return false;
        }
    }

    /**
     * この録画に関係するファイルの合計サイズを求める。
     *
     * <p>{@link RecordingReconciler} が<b>救済を再試行してよいかを、外部プロセスを起動せずに
     * 判断するため</b>に使う。一度 {@code ffmpeg} で救済できなかった録画をそのまま毎巡回
     * 試し続けると、数GBのファイルに対するプロセス起動を永久に繰り返すことになる。かといって
     * 二度と試さないことにすると、あとから断片が揃った場合に救済できない（それがこのクラス群の
     * 目的なので本末転倒になる）。そこで「前回失敗したときとファイルの顔ぶれ・大きさが同じなら
     * 結果も同じ」とみなせるよう、状態の変化だけを安く見分けられる値を返す。
     *
     * <p>完成ファイルだけでなく結合前の断片（{@code {動画ID}.f137.mp4} 等）も合計するため、
     * 断片が1つ増えた・書き込みが進んだといった変化も拾える。
     *
     * @param recording 対象の録画履歴
     * @return 関係するファイルの合計サイズ（バイト）。1つも無ければ 0
     */
    public long totalFileSizeFor(Recording recording) {
        Path file = resolveFilePath(recording);
        Path directory = file.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }

        String videoId = extractVideoId(file);
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(candidate -> videoId.equals(extractVideoId(candidate)))
                    .mapToLong(this::sizeOrZero)
                    .sum();
        } catch (IOException e) {
            log.warn("録画ファイルの合計サイズを求められませんでした: directory={}", directory, e);
            return 0;
        }
    }

    /**
     * {@code recordings/} ディレクトリの使用量を、チャンネル別に集計する。
     *
     * @return 合計使用量とチャンネル別の内訳
     */
    public DiskUsageResponse calculateUsage() {
        Path baseDirectory = Path.of(monitorProperties.recording().directory());
        if (!Files.isDirectory(baseDirectory)) {
            return new DiskUsageResponse(0, List.of());
        }

        Map<String, String> channelNames = monitoredChannelRepository.findAll().stream()
                .collect(Collectors.toMap(MonitoredChannel::getYoutubeChannelId, MonitoredChannel::getChannelName));
        Map<String, Set<String>> unlinkedVideoIds = unlinkedVideoIdsByDirectory();

        List<ChannelDiskUsage> byChannel = new ArrayList<>();
        try (Stream<Path> channelDirectories = Files.list(baseDirectory)) {
            for (Path dir : channelDirectories.filter(Files::isDirectory).toList()) {
                String channelId = dir.getFileName().toString();
                boolean registered = channelNames.containsKey(channelId);
                byChannel.add(new ChannelDiskUsage(
                        channelId,
                        displayNameFor(channelId, channelNames, unlinkedVideoIds),
                        sumFileSizes(dir),
                        registered));
            }
        } catch (IOException e) {
            log.error("録画ディレクトリの走査に失敗しました: {}", baseDirectory, e);
        }

        byChannel.sort(Comparator.comparingLong(ChannelDiskUsage::bytes).reversed());
        long totalBytes = byChannel.stream().mapToLong(ChannelDiskUsage::bytes).sum();
        return new DiskUsageResponse(totalBytes, byChannel);
    }

    /**
     * ディレクトリ名に対応する表示名を決める。
     *
     * <p>「登録中」「監視登録していない（ダウンロードで入った）」「削除済み」の 3 通りを区別する。
     * <b>削除済みと未登録を同じ言葉にしてはいけない。</b>一括削除の対象になるのは削除済みの方だけで、
     * 未登録（履歴が残っているもの）は対象外として残るため、同じ表示だと
     * 「削除済みと出ているのに一括削除しても消えない」という説明のつかない挙動に見える。
     *
     * @param channelId        ディレクトリ名（チャンネル識別子）
     * @param channelNames     登録中チャンネルの識別子と表示名の対応
     * @param unlinkedVideoIds チャンネルに紐づかない録画が置かれているディレクトリと、その動画 ID
     * @return 画面に出す表示名
     */
    private String displayNameFor(String channelId, Map<String, String> channelNames,
                                  Map<String, Set<String>> unlinkedVideoIds) {
        String registeredName = channelNames.get(channelId);
        if (registeredName != null) {
            return registeredName;
        }
        return unlinkedVideoIds.containsKey(channelId)
                ? RecordingResponse.UNLINKED_CHANNEL_NAME
                : UNKNOWN_CHANNEL_NAME;
    }

    /**
     * チャンネルに紐づいていない録画を、置かれているディレクトリごとにまとめる。
     *
     * <p>URL 指定のダウンロードで取り込んだ録画がこれにあたる。<b>これらは
     * 「登録中のどのチャンネル ID とも一致しないディレクトリ」に置かれるため、
     * 履歴を見ずに掃除すると削除済みチャンネルの置き土産と見分けがつかない。</b>
     * 一度 DB から引いておき、走査中のディレクトリごとに照合する。
     *
     * @return ディレクトリ名と、そこに置かれた紐づかない録画の動画 ID
     */
    private Map<String, Set<String>> unlinkedVideoIdsByDirectory() {
        return recordingRepository.findByChannelIsNull().stream()
                .collect(Collectors.groupingBy(
                        recording -> directoryNameOf(recording.getFilePath()),
                        Collectors.mapping(Recording::getVideoId, Collectors.toSet())));
    }

    /**
     * 録画履歴の相対パス（{@code {ディレクトリ名}/{動画ID}.mp4}）から、ディレクトリ名を取り出す。
     *
     * <p>区切り文字は OS に依らず {@code /} で保存している（{@link #toRelativePath(Path)} 参照）。
     *
     * @param filePath 録画履歴の相対パス
     * @return ディレクトリ名。ディレクトリが無い形式なら空文字
     */
    private static String directoryNameOf(String filePath) {
        int separatorIndex = filePath.indexOf('/');
        return separatorIndex < 0 ? "" : filePath.substring(0, separatorIndex);
    }

    /**
     * 監視対象から削除済みのチャンネルの録画ファイルと、登録中チャンネルに残った
     * 履歴の無い断片ファイルをまとめて削除する。
     *
     * <p>対象は2種類ある。
     * <ul>
     *   <li>削除済みチャンネルのディレクトリ丸ごと … チャンネルを削除すると通知履歴・録画履歴は
     *       DB の連鎖削除で消えるが、録画ファイル本体はディスクに残る設計になっている
     *       （消したくない場合があるため）。DB 上の履歴は既に消えているため、
     *       ここはファイルシステムだけの操作になる。</li>
     *   <li>登録中チャンネルのディレクトリ内の断片ファイル … 録画が失敗してマージ前に
     *       中断されると {@code yt-dlp} の断片ファイルが残る。以前は
     *       {@link #deleteFile(Recording)} が完成ファイル1つしか消さなかったため、
     *       その履歴を削除しても断片ファイルだけが残り続けた（実際に発生した：計 5.8GB）。
     *       {@link RecordingRepository#findVideoIdsByChannelYoutubeChannelId(String)} で
     *       「そのチャンネルの履歴に存在する動画ID」を取得し、それに無い動画IDのファイルを
     *       孤立とみなして削除する。</li>
     * </ul>
     *
     * <p><b>「録画履歴が残っているファイルは消さない」が唯一の判断基準。</b>
     * 登録中チャンネルかどうかだけで決めると、URL 指定でダウンロードした
     * <b>チャンネルに紐づかない録画を丸ごと消してしまう</b>（置き場所が
     * 「登録中のどのチャンネル ID とも一致しないディレクトリ」になるため、
     * 削除済みチャンネルの置き土産と見分けがつかない）。そこで登録の有無に関わらず、
     * 履歴のある動画のファイルは残し、履歴が 1 件も無いディレクトリだけを丸ごと削除する。
     *
     * <p><b>録画がまだ進行中のもの（チャンネル・動画のどちらも）は対象から外す。</b>
     * {@code yt-dlp} はチャンネルを削除しても JVM とは独立に動き続けるため、
     * 書き込み中のファイルを消すとプロセス側がエラーになったり中途半端なファイルが残る。
     * 判定にはコマンドラインにチャンネル ID・動画ID（どちらも出力先パスに含まれる）が
     * 現れるかを使う。
     *
     * @return 削除結果の集計
     */
    public OrphanedCleanupResponse deleteOrphanedRecordings() {
        Path baseDirectory = Path.of(monitorProperties.recording().directory());
        if (!Files.isDirectory(baseDirectory)) {
            return new OrphanedCleanupResponse(0, 0, 0, List.of());
        }

        Set<String> registeredChannelIds = monitoredChannelRepository.findAll().stream()
                .map(MonitoredChannel::getYoutubeChannelId)
                .collect(Collectors.toSet());
        Map<String, Set<String>> unlinkedVideoIds = unlinkedVideoIdsByDirectory();

        int deletedChannels = 0;
        int deletedFiles = 0;
        long freedBytes = 0;
        List<String> skippedChannels = new ArrayList<>();

        try (Stream<Path> channelDirectories = Files.list(baseDirectory)) {
            for (Path dir : channelDirectories.filter(Files::isDirectory).toList()) {
                String channelId = dir.getFileName().toString();
                boolean registered = registeredChannelIds.contains(channelId);

                // 履歴のある動画は、チャンネルに紐づいていてもいなくても消してはいけない
                Set<String> knownVideoIds = new HashSet<>(unlinkedVideoIds.getOrDefault(channelId, Set.of()));
                if (registered) {
                    knownVideoIds.addAll(recordingRepository.findVideoIdsByChannelYoutubeChannelId(channelId));
                }

                if (registered || !knownVideoIds.isEmpty()) {
                    FragmentSweepResult swept = sweepOrphanedFragments(dir, channelId, knownVideoIds);
                    deletedFiles += swept.filesRemoved();
                    freedBytes += swept.bytesFreed();
                    continue;
                }
                if (processLauncher.isRunningWithCommandLineContaining(channelId)) {
                    log.info("録画が進行中のため削除を見送りました: channel={}", channelId);
                    skippedChannels.add(channelId);
                    continue;
                }

                long sizeBeforeDelete = sumFileSizes(dir);
                int removed = deleteDirectoryRecursively(dir);
                if (removed > 0 || !Files.exists(dir)) {
                    deletedChannels++;
                    deletedFiles += removed;
                    freedBytes += sizeBeforeDelete;
                    log.info("削除済みチャンネルの録画を削除しました: channel={}, files={}, bytes={}",
                            channelId, removed, sizeBeforeDelete);
                }
            }
        } catch (IOException e) {
            log.error("録画ディレクトリの走査に失敗しました: {}", baseDirectory, e);
        }

        return new OrphanedCleanupResponse(deletedChannels, deletedFiles, freedBytes, skippedChannels);
    }

    /**
     * 削除の集計値だけを持つ内部専用の結果。
     *
     * @param filesRemoved 削除できたファイルの数
     * @param bytesFreed   解放できた容量（バイト）
     */
    private record FragmentSweepResult(int filesRemoved, long bytesFreed) {
    }

    /**
     * ディレクトリ内で、録画履歴に存在しない動画IDのファイルを削除する。
     *
     * <p>動画IDごとにファイルをまとめ、履歴が一件も無く、かつ録画中でもないものだけを
     * 孤立ファイルとして削除する。
     *
     * @param channelDirectory 対象のディレクトリ
     * @param channelId        対象のディレクトリ名（チャンネル識別子）。ログに出す
     * @param knownVideoIds    このディレクトリに履歴がある動画ID（削除してはいけないもの）
     * @return 削除できたファイル数と解放できた容量
     */
    private FragmentSweepResult sweepOrphanedFragments(Path channelDirectory, String channelId,
                                                       Set<String> knownVideoIds) {
        int filesRemoved = 0;
        long bytesFreed = 0;
        try (Stream<Path> files = Files.list(channelDirectory)) {
            Map<String, List<Path>> filesByVideoId = files.filter(Files::isRegularFile)
                    .collect(Collectors.groupingBy(RecordingFileService::extractVideoId));

            for (Map.Entry<String, List<Path>> entry : filesByVideoId.entrySet()) {
                String videoId = entry.getKey();
                if (knownVideoIds.contains(videoId)) {
                    continue;
                }
                if (processLauncher.isRunningWithCommandLineContaining(videoId)) {
                    log.info("録画が進行中のため断片ファイルの削除を見送りました: channel={}, video={}",
                            channelId, videoId);
                    continue;
                }

                long sizeBeforeDelete = entry.getValue().stream().mapToLong(this::sizeOrZero).sum();
                int removed = deleteFiles(entry.getValue());
                if (removed > 0) {
                    filesRemoved += removed;
                    bytesFreed += sizeBeforeDelete;
                    log.info("録画履歴の無い断片ファイルを削除しました: channel={}, video={}, files={}, bytes={}",
                            channelId, videoId, removed, sizeBeforeDelete);
                }
            }
        } catch (IOException e) {
            log.error("録画ディレクトリの走査に失敗しました: {}", channelDirectory, e);
        }
        return new FragmentSweepResult(filesRemoved, bytesFreed);
    }

    /**
     * ディレクトリを中身ごと削除する。
     *
     * <p>ファイルを先に消してからディレクトリを消す必要があるため、深い方から順に削除する。
     * 個々の削除に失敗しても中断せず、消せたものだけを数える（一部が消せなくても
     * 残りの掃除は進めた方が利用者にとって有益なため）。
     *
     * @param directory 削除対象のディレクトリ
     * @return 削除できたファイルの数（ディレクトリ自体は数えない）
     */
    private int deleteDirectoryRecursively(Path directory) {
        int deletedFiles = 0;
        try (Stream<Path> paths = Files.walk(directory)) {
            // 深い方（ファイル）から削除しないとディレクトリが空にならず消せない
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                boolean isFile = Files.isRegularFile(path);
                try {
                    Files.delete(path);
                    if (isFile) {
                        deletedFiles++;
                    }
                } catch (IOException e) {
                    log.warn("録画ファイルの削除に失敗しました: {}", path, e);
                }
            }
        } catch (IOException e) {
            log.error("削除対象の走査に失敗しました: {}", directory, e);
        }
        return deletedFiles;
    }

    /**
     * ディレクトリ配下（サブディレクトリを含む）の全ファイルサイズを合計する。
     *
     * @param directory 集計対象のディレクトリ
     * @return 合計サイズ（バイト）。走査に失敗した場合は 0
     */
    private long sumFileSizes(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(this::sizeOrZero).sum();
        } catch (IOException e) {
            log.error("録画ファイルの集計に失敗しました: {}", directory, e);
            return 0;
        }
    }

    /**
     * ファイルサイズを取得する。取得できなければ（他プロセスが削除した等）0 として扱う。
     *
     * @param file 対象ファイル
     * @return サイズ（バイト）
     */
    private long sizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }
}
