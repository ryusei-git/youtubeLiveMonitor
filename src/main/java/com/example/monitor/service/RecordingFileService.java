package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.dto.DiskUsageResponse.ChannelDiskUsage;
import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.DirectorySizeUtils;
import com.example.monitor.util.YtDlpLogFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
     * <p>yt-dlp のログ（{@code logs/yt-dlp/<動画ID>.log}）も一緒に消す。録画を消した後に残しても、調べる相手の録画が無いため。
     * 録画ファイルが見つからない経路でもログは残っているので、早期 return より前の先頭で消す。
     *
     * @param recording 削除対象の録画履歴
     */
    public void deleteFile(Recording recording) {
        deleteYtDlpLog(recording.getVideoId());

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
     * 動画 1 本ぶんの yt-dlp のログを削除する。
     *
     * <p>失敗しても例外は投げない（{@link #deleteFile(Recording)} と同じく、DB 削除に追随する後始末のため）。
     *
     * @param videoId 対象の動画 ID
     */
    private void deleteYtDlpLog(String videoId) {
        Path path = YtDlpLogFile.of(videoId);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("yt-dlp のログの削除に失敗しました: {}", path, e);
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
     * <p>個々の削除に失敗しても中断せず、消せたものだけを数える（一部が消せなくても、残りを消した方が利用者にとって有益なため）。
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
                    .mapToLong(DirectorySizeUtils::sizeOf)
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
                        DirectorySizeUtils.sizeOf(dir),
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
}
