package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.MyUploadListResponse;
import com.example.monitor.dto.MyUploadResponse;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.exception.UploadRejectedException;
import com.example.monitor.exception.UploadTooLargeException;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.DiskSpaceUtils;
import com.example.monitor.util.FileNameUtils;
import com.example.monitor.util.LogValueSanitizer;
import com.example.monitor.util.TitleGenreExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 利用者の手元の動画を受け取り、録画と同じくアーカイブで再生できる形で取り込む。
 *
 * <h2>受け取り中の情報を DB に入れない理由</h2>
 * すべて終わってから {@code COMPLETED} の行を 1 行だけ作る。受け取り中の行を作ると、切断や再起動のたびに
 * {@link RecordingReconciler} が相手をする行が残る。受け取りは録画と違って再開できないので、残しても直せない。
 *
 * <h2>入力を他人のファイルとして扱う理由</h2>
 * 受け取ったファイルを読む {@code ffprobe} と {@code ffmpeg} には、必ず {@link #INPUT_WHITELIST} を付ける。
 * {@code ffmpeg} の入力は動画とは限らず、HLS や concat のプレイリストなら、書かれた先のファイルや URL を
 * 読みに行く。名前だけ {@code .mp4} にして上げられると、サーバーの中のファイルや内側の URL を読ませられる。
 * ほかのクラスの {@code ffprobe}・{@code ffmpeg}（再生時間・サムネイル）が読むのは、ここで詰め替えた後の
 * mp4 なので要らない。受け取ったファイルをそれらへ渡さないこと。
 *
 * <h2>詰め替える理由</h2>
 * 再生の互換性のほかに、位置情報を落とすため。iPhone の動画には撮影場所が入っていて、取り込んだ動画は
 * 全員に見える。映像は再エンコードしない（CPU が足りない）ので、映像の中に埋め込まれた情報までは落とせない。
 *
 * <h2>確認と予約を分けない理由</h2>
 * 本数と空き容量は {@link #reserveSlot} が 1 つのロックの中で確かめて予約する。分けると、同時に来た要求が
 * どちらも判定を通って上限を超える（{@link ActiveVideoJobs} と同じ考え方）。
 *
 * <p>{@code cli} プロファイルでは作らない。起動時の掃除（{@link #deleteLeftovers()}）が、動いているサービスの
 * 受け取り中のファイルを消すため（CLI はサービスと同じ作業ディレクトリで動く）。
 */
@Service
@Profile("!cli")
@RequiredArgsConstructor
@Slf4j
public class VideoUploadService {

    /** 上げた動画の置き場所（録画ディレクトリ配下）。チャンネル識別子と衝突しない名前にしてある。 */
    private static final String UPLOADS_DIRECTORY = "uploads";

    /**
     * 動画 ID の接頭辞。動画 ID はファイル名の最初の {@code .} までなので（{@code RecordingPathUtils}）、
     * {@code .} を含めない。
     */
    private static final String VIDEO_ID_PREFIX = "upload-";

    /** 受け取り中のファイルの接尾辞。 */
    private static final String RECEIVING_SUFFIX = ".uploading";

    /** 詰め替え中のファイルの接尾辞。 */
    private static final String REMUXING_SUFFIX = ".remuxing.mp4";

    /** 完成したファイルの接尾辞。 */
    private static final String COMPLETED_SUFFIX = ".mp4";

    /** 1 バイトあたりの GB。{@link DiskSpaceUtils} と同じく 1024 の 3 乗で数える。 */
    private static final long BYTES_PER_GB = 1024L * 1024 * 1024;

    /** 同時に受け取る本数の上限（全員の合計）。詰め替えが終わるまで 1 本と数える。 */
    private static final int MAX_CONCURRENT_UPLOADS = 3;

    /** 題名の上限（文字数）。 */
    private static final int MAX_TITLE_LENGTH = 200;

    /**
     * 入力として受け付ける形式だけを許す {@code ffprobe}・{@code ffmpeg} の指定。
     * 理由はクラスの JavaDoc を参照。{@code -protocol_whitelist} はファイル以外を読ませず、
     * {@code -format_whitelist} は HLS・concat のような、別のファイルを読みに行く形式を開かせない。
     * どちらも入力の前（{@code -i} より前）に置くこと。
     */
    private static final List<String> INPUT_WHITELIST =
            List.of("-protocol_whitelist", "file", "-format_whitelist", "mov,matroska");

    /** {@code ffprobe} の応答待ちの上限（秒）。情報を読むだけなので長くはかからない。 */
    private static final long PROBE_TIMEOUT_SECONDS = 300;

    /** 詰め替えの応答待ちの上限（秒）。上限の大きさの動画を、音声の再エンコードを含めて詰め替える時間。 */
    private static final long REMUX_TIMEOUT_SECONDS = 3600;

    /** 受け付ける形式（{@code ffprobe} の {@code format_name} の先頭）。 */
    private static final List<String> ACCEPTED_FORMAT_PREFIXES = List.of("mov,", "matroska,");

    /** 受け付ける映像の形式。mp4 へ再エンコードせずに詰められるものだけ。 */
    private static final Set<String> ACCEPTED_VIDEO_CODECS = Set.of("h264", "hevc", "vp9", "av1");

    /** そのまま mp4 に詰められる音声の形式。ほかは AAC にする。 */
    private static final Set<String> COPYABLE_AUDIO_CODECS = Set.of("aac", "mp3");

    /** 取り込めない動画への文言。 */
    private static final String UNSUPPORTED_MESSAGE =
            "この動画は取り込めません（対応形式: MP4・MOV・WebM・MKV）";

    /** 空きが足りないときの文言。 */
    private static final String NO_SPACE_MESSAGE = "ディスクの空きが足りません";

    private final MonitorProperties monitorProperties;
    private final ActiveVideoJobs activeVideoJobs;
    private final ExternalCommandRunner externalCommandRunner;
    private final VideoMetadataExtractor videoMetadataExtractor;
    private final RecordingFileService recordingFileService;
    private final RecordingHistoryService recordingHistoryService;
    private final RecordingRepository recordingRepository;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    /** 1 本の大きさの上限（GB）。 */
    @Value("${monitor.upload.max-file-gb:20}")
    private long maxFileGb = 20;

    /** 1 人が置ける合計の上限（GB）。 */
    @Value("${monitor.upload.user-quota-gb:100}")
    private long userQuotaGb = 100;

    /**
     * 残す空き容量（GB）。録画を始めるしきい値と同じ設定を使う。ここを割ると録画が始まらなくなるため。
     *
     * <p>初期値を 0（確認しない）にしている理由は {@link VideoDownloadService} の同名のフィールドを参照。
     */
    @Value("${monitor.recording.min-free-gb:20}")
    private long minFreeGb = 0;

    /**
     * 監視を動かす起動か。確認用の起動（{@code bin/preview.sh}）は本番と同じ録画フォルダを指すので、
     * 起動時の掃除をすると本番の受け取り中のファイルを消す（{@link RecordingReconciler} と同じ設定に従う）。
     */
    @Value("${monitor.scheduling.enabled:true}")
    private boolean schedulingEnabled = true;

    /**
     * 受け取り中の利用者のログイン名と {@code Content-Length}。このオブジェクトをロックにして読み書きする。
     *
     * <p>メモリだけに持つ。再起動すると受け取りも切れるので、{@link VideoDownloadService} の枠と違い、
     * 実際の受け取りとずれることも無い。
     */
    private final Map<String, Long> activeUploads = new HashMap<>();

    /**
     * 動画を受け取り、取り込む。すべて終わるまで戻らない。
     *
     * <p>上限の判定は本文を読む前に済ませる。読んでから断ると、大きな動画を送り切らせることになるため。
     * 失敗したとき（切断・取り込めない動画を含む）は、ファイルをすべて消し、予約を解放し、DB に行を残さない。
     *
     * @param username      上げる利用者のログイン名
     * @param fileName      元のファイル名。題名を省いたときだけ使い、パスには使わない。無ければ {@code null}
     * @param title         題名。無ければ {@code null}
     * @param contentLength 本文の長さ（バイト）。無ければ負の値
     * @param body          本文
     * @return 取り込んだ動画
     * @throws IllegalArgumentException 題名・長さが不正な場合、取り込めない動画の場合、本文が途中で終わった
     *                                  場合（400）
     * @throws UploadTooLargeException  1 本の上限を超える場合（413）
     * @throws UploadRejectedException  空き容量・利用者の容量・同時の本数の都合で受け付けられない場合（409）
     * @throws IOException              本文を読めない（接続が切れた）場合、ファイルを書けない場合
     */
    public MyUploadResponse upload(String username, String fileName, String title, long contentLength,
                                   InputStream body) throws IOException {
        String resolvedTitle = resolveTitle(title, fileName);
        if (contentLength <= 0) {
            throw new IllegalArgumentException("Content-Length を指定してください");
        }
        if (contentLength > maxFileGb * BYTES_PER_GB) {
            throw new UploadTooLargeException(maxFileGb);
        }

        reserveSlot(username, contentLength);
        try {
            // 枠を取った後に数える。先に数えると、本人の前の 1 本が終わった分を見落とす
            if (recordingRepository.sumFileSizeBytesByUploadedBy(username) + contentLength
                    > userQuotaGb * BYTES_PER_GB) {
                throw new UploadRejectedException("アップロードできる容量（" + userQuotaGb + "GB）を超えます");
            }
            String videoId = VIDEO_ID_PREFIX + UUID.randomUUID();
            if (!activeVideoJobs.reserve(videoId)) {
                throw new IllegalStateException("動画 ID が重なりました: " + videoId);
            }
            try {
                return ingest(username, videoId, resolvedTitle, contentLength, body);
            } finally {
                // 行を保存してから外す。先に外すと、孤立ファイルの掃除が履歴の無いファイルとして消しうる
                activeVideoJobs.release(videoId);
            }
        } finally {
            releaseSlot(username);
        }
    }

    /**
     * 題名を決める。
     *
     * @param title    指定された題名
     * @param fileName 元のファイル名
     * @return 前後の空白を除いた題名
     * @throws IllegalArgumentException どちらも空の場合、{@value #MAX_TITLE_LENGTH} 文字を超える場合
     */
    private static String resolveTitle(String title, String fileName) {
        String resolved = title == null || title.isBlank() ? baseNameOf(fileName) : title.strip();
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("題名（title）かファイル名（fileName）を指定してください");
        }
        if (resolved.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("題名は " + MAX_TITLE_LENGTH + " 字までです");
        }
        return resolved;
    }

    /**
     * ファイル名から拡張子を除く。
     *
     * @param fileName ファイル名。{@code null} 可
     * @return 拡張子を除いた名前。{@code null} なら空文字
     */
    private static String baseNameOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.strip();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : FileNameUtils.stripExtension(name, name.substring(dot)).strip();
    }

    /**
     * 本数と空き容量を確かめて、枠を取る。確認と予約を 1 つのロックの中で行う（クラスの JavaDoc 参照）。
     *
     * <p>空き容量は、受け取り中の分とこの 1 本を受け取った後に {@link #minFreeGb} を残せるかで見る。
     * 録画を止めないため。受け取り中の分は {@code Content-Length} の合計で数える。受け取り済みの分は
     * 既に空きから引かれているので二重に数えるが、詰め替えの間はもう 1 本分を使うので、少なく見積もるよりよい。
     *
     * <p>加えて、この 1 本の詰め替えの山も見る。詰め替えの間は受け取ったファイルと出力の 2 本分になるので、
     * ここで見ないと、大きな動画を送り切った後で {@link #requireSpaceToRemux} に断られる。
     *
     * @param username      上げる利用者のログイン名
     * @param contentLength 本文の長さ（バイト）
     * @throws UploadRejectedException 本人が上げている途中の場合、全体の本数が上限の場合、空きが足りない場合
     */
    private void reserveSlot(String username, long contentLength) {
        synchronized (activeUploads) {
            if (activeUploads.containsKey(username)) {
                throw new UploadRejectedException("アップロードの途中です");
            }
            if (activeUploads.size() >= MAX_CONCURRENT_UPLOADS) {
                throw new UploadRejectedException("ほかのアップロードが終わるのを待ってください");
            }
            long receivingBytes = activeUploads.values().stream().mapToLong(Long::longValue).sum();
            long floorBytes = Math.max(minFreeGb * BYTES_PER_GB,
                    contentLength + DiskSpaceUtils.reserveBytes(minFreeGb));
            if (lacksSpace(receivingBytes + contentLength, floorBytes)) {
                throw new UploadRejectedException(NO_SPACE_MESSAGE);
            }
            activeUploads.put(username, contentLength);
        }
    }

    /**
     * 枠を外す。
     *
     * @param username 上げた利用者のログイン名
     */
    private void releaseSlot(String username) {
        synchronized (activeUploads) {
            activeUploads.remove(username);
        }
    }

    /**
     * 空き容量が足りないかを返す。
     *
     * @param addedBytes これから増える量（バイト）
     * @param floorBytes 増えた後に残したい空き（バイト）
     * @return 空き容量を読めて足りなければ {@code true}。{@link #minFreeGb} が 0 以下・空き容量を読めないときは
     *         {@code false}（「判定できなかった」を「足りない」と扱わない）
     */
    private boolean lacksSpace(long addedBytes, long floorBytes) {
        if (minFreeGb <= 0) {
            return false;
        }
        DiskSpaceUtils.Capacity disk = DiskSpaceUtils.read(uploadsDirectory());
        return disk.error() == null && disk.usableBytes() != null
                && disk.usableBytes() < addedBytes + floorBytes;
    }

    /**
     * 受け取りから行の保存までを行う。失敗したら、この動画のファイルをすべて消す。
     *
     * @param username      上げる利用者のログイン名
     * @param videoId       動画 ID（予約済み）
     * @param title         題名
     * @param contentLength 本文の長さ（バイト）
     * @param body          本文
     * @return 取り込んだ動画
     * @throws IOException 本文を読めない場合、ファイルを書けない場合
     */
    private MyUploadResponse ingest(String username, String videoId, String title, long contentLength,
                                    InputStream body) throws IOException {
        Path directory = uploadsDirectory();
        Files.createDirectories(directory);
        Path receiving = directory.resolve(videoId + RECEIVING_SUFFIX);
        Path remuxing = directory.resolve(videoId + REMUXING_SUFFIX);
        Path completed = directory.resolve(videoId + COMPLETED_SUFFIX);

        boolean saved = false;
        try {
            receive(body, receiving, contentLength);
            ProbedVideo video = probe(receiving);
            requireSpaceToRemux(contentLength);
            remux(receiving, remuxing, video);
            Files.move(remuxing, completed, StandardCopyOption.ATOMIC_MOVE);
            // 再生時間とサムネイルを読む前に、受け取った分の空きを返す
            Files.delete(receiving);

            Recording recording = save(username, videoId, title, completed);
            saved = true;
            auditLogger.recordByCurrentUser(AuditAction.RECORDING_UPLOAD, AuditOutcome.SUCCESS, "RECORDING",
                    String.valueOf(recording.getId()), "video=" + videoId + ", title=" + title);
            log.info("動画のアップロードを取り込みました: id={}, video={}, title={}, size={}",
                    recording.getId(), videoId, LogValueSanitizer.sanitize(title), recording.getFileSizeBytes());
            return MyUploadResponse.from(recording);
        } finally {
            deleteQuietly(receiving);
            deleteQuietly(remuxing);
            if (!saved) {
                deleteQuietly(completed);
                deleteQuietly(videoMetadataExtractor.thumbnailPathFor(completed));
                log.info("動画のアップロードを取り込めなかったため、ファイルを消しました: video={}", videoId);
            }
        }
    }

    /**
     * 本文を読みながらファイルへ書く。
     *
     * <p>マルチパートにしていないのは、一時ファイルが {@code /tmp}（本番は tmpfs）に置かれ、大きな動画で
     * メモリを食い潰すため。
     *
     * @param body          本文
     * @param receiving     書き込み先
     * @param contentLength 本文の長さ（バイト）
     * @throws IOException              本文を読めない場合、ファイルを書けない場合
     * @throws IllegalArgumentException 受け取った長さが {@code contentLength} と違う場合
     */
    private void receive(InputStream body, Path receiving, long contentLength) throws IOException {
        long received;
        try (OutputStream output = Files.newOutputStream(receiving,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            received = body.transferTo(output);
        }
        if (received != contentLength) {
            throw new IllegalArgumentException("動画を最後まで受け取れませんでした");
        }
    }

    /**
     * 受け取ったファイルの形式を確かめる。
     *
     * @param file 受け取ったファイル
     * @return 詰め替えに要る情報
     * @throws IllegalArgumentException 取り込めない動画の場合
     */
    private ProbedVideo probe(Path file) {
        List<String> command = new ArrayList<>(List.of("ffprobe", "-v", "error"));
        command.addAll(INPUT_WHITELIST);
        command.addAll(List.of(
                "-show_entries", "format=format_name:stream=codec_type,codec_name",
                "-of", "json",
                file.toString()));
        String output = externalCommandRunner.run(command, file, PROBE_TIMEOUT_SECONDS)
                .orElseThrow(VideoUploadService::unsupportedVideo);

        JsonNode probed = parseProbeOutput(output);
        String format = probed.path("format").path("format_name").asString("");
        String videoCodec = firstCodec(probed, "video");
        if (ACCEPTED_FORMAT_PREFIXES.stream().noneMatch(format::startsWith)
                || !ACCEPTED_VIDEO_CODECS.contains(videoCodec)) {
            log.info("取り込めない動画でした: file={}, format={}, video={}",
                    file.getFileName(), format, videoCodec);
            throw unsupportedVideo();
        }
        return new ProbedVideo(videoCodec, firstCodec(probed, "audio"));
    }

    /**
     * {@code ffprobe} の出力を読む。
     *
     * <p>{@link ExternalCommandRunner} は標準エラーも同じ出力として受け取る
     * （{@link DefaultProcessLauncher}）ので、少し壊れた動画では {@code -v error} でも JSON の前に行が混ざる。
     * JSON の始まりの行から読む。
     *
     * @param output {@code ffprobe} の出力
     * @return 読み取った内容
     * @throws IllegalArgumentException JSON として読めない場合
     */
    private JsonNode parseProbeOutput(String output) {
        int start = output.indexOf("{\n");
        int end = output.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw unsupportedVideo();
        }
        try {
            return objectMapper.readTree(output.substring(start, end + 1));
        } catch (JacksonException e) {
            log.warn("ffprobe の出力を読めませんでした", e);
            throw unsupportedVideo();
        }
    }

    /**
     * 指定した種類の 1 つ目のストリームの形式を返す。
     *
     * <p>すべてのストリームから探さない。詰め替えるのは 1 つ目だけなので、2 つ目が対応形式でも取り込めない。
     *
     * @param probed    {@code ffprobe} の出力
     * @param codecType {@code video} か {@code audio}
     * @return 形式の名前。そのストリームが無ければ空文字
     */
    private static String firstCodec(JsonNode probed, String codecType) {
        for (JsonNode stream : probed.path("streams")) {
            if (codecType.equals(stream.path("codec_type").asString(""))) {
                return stream.path("codec_name").asString("");
            }
        }
        return "";
    }

    /**
     * 詰め替えに要る空きがあるかを確かめる。
     *
     * <p>受け取りを始めたときに見ているが、受け取りの間に録画が空きを使っていることがある。足りないまま
     * 始めると {@code ffmpeg} が空きを 0 まで使い切り、同じファイルシステムの H2 とログも書けなくなる。
     * 基準は録画の詰め替え（{@link RecordingSalvager}）と同じ、出力の分と
     * {@link DiskSpaceUtils#reserveBytes(long)}。
     *
     * @param size 受け取ったファイルの大きさ（バイト）
     * @throws UploadRejectedException 空きが足りない場合
     */
    private void requireSpaceToRemux(long size) {
        if (lacksSpace(size, DiskSpaceUtils.reserveBytes(minFreeGb))) {
            throw new UploadRejectedException(NO_SPACE_MESSAGE);
        }
    }

    /**
     * 1 つ目の映像と 1 つ目の音声を mp4 へ詰め替える。メタデータは位置情報を含めてすべて落とす。
     *
     * <p>{@code -tag:v hvc1} は、{@code hev1} のままだと Safari が再生しないため。
     * 成否は {@code ffmpeg} の終了コードに加えて、出来たファイルの中身で見る（{@link #hasVideoFrames}）。
     *
     * @param input  受け取ったファイル
     * @param output 出力先
     * @param video  {@link #probe} の結果
     * @throws IllegalArgumentException 詰め替えられなかった場合
     */
    private void remux(Path input, Path output, ProbedVideo video) {
        // 進捗と警告は出させない理由は RecordingSalvager の詰め替えと同じ
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-y", "-v", "error", "-nostats"));
        command.addAll(INPUT_WHITELIST);
        command.addAll(List.of(
                "-i", input.toString(),
                "-map", "0:v:0",
                "-map", "0:a:0?",
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-c:v", "copy"));
        if ("hevc".equals(video.videoCodec())) {
            command.addAll(List.of("-tag:v", "hvc1"));
        }
        if (COPYABLE_AUDIO_CODECS.contains(video.audioCodec())) {
            command.addAll(List.of("-c:a", "copy"));
        } else {
            command.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
        }
        command.addAll(List.of("-movflags", "+faststart", output.toString()));

        if (externalCommandRunner.run(command, input, REMUX_TIMEOUT_SECONDS).isEmpty()
                || !Files.isRegularFile(output) || !hasVideoFrames(output)) {
            log.warn("動画を詰め替えられませんでした: file={}", input.getFileName());
            throw unsupportedVideo();
        }
    }

    /**
     * 詰め替えた結果に映像が入っているかを返す。
     *
     * <p>{@code ffmpeg} の終了コードとファイルの有無だけでは足りない。本体が途中で切れた動画では、
     * 情報（{@code moov}）を読めるので {@link #probe} を通り、{@code ffmpeg} は終了コード 0 のまま
     * 映像の無い mp4 を作る（実測で 1KB ほど）。{@code COMPLETED} の行にすると、再生できない動画が全員の
     * アーカイブに並ぶ（{@link RecordingSalvager} が差し替えの前に確かめるのと同じ理由）。
     *
     * @param file 詰め替えた結果
     * @return 映像のフレームが 1 つ以上あれば {@code true}
     */
    private boolean hasVideoFrames(Path file) {
        List<String> command = List.of(
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=nb_frames",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.toString());
        return externalCommandRunner.run(command, file, PROBE_TIMEOUT_SECONDS)
                .filter(frames -> frames.strip().matches("[1-9][0-9]*"))
                .isPresent();
    }

    /**
     * 完成したファイルの行を作る。
     *
     * <p>再生時間とサムネイルは失敗しても取り込みを止めない（{@link VideoMetadataExtractor} の JavaDoc 参照）。
     * 無いものは {@link RecordingReconciler} が後で作る。
     *
     * @param username  上げた利用者のログイン名
     * @param videoId   動画 ID
     * @param title     題名
     * @param completed 完成したファイル
     * @return 保存した行
     * @throws IOException 大きさを読めない場合
     */
    private Recording save(String username, String videoId, String title, Path completed) throws IOException {
        Integer duration = videoMetadataExtractor.extractDurationSeconds(completed).orElse(null);
        String thumbnail = duration == null ? null
                : videoMetadataExtractor.extractThumbnail(completed, duration)
                        .map(recordingFileService::toRelativePath)
                        .orElse(null);
        LocalDateTime now = LocalDateTime.now();
        return recordingRepository.save(Recording.builder()
                .videoId(videoId)
                .videoTitle(title)
                .genre(TitleGenreExtractor.extract(title))
                .filePath(recordingFileService.toRelativePath(completed))
                .fileSizeBytes(Files.size(completed))
                .durationSeconds(duration)
                .thumbnailPath(thumbnail)
                .status(RecordingStatus.COMPLETED)
                .startedAt(now)
                .completedAt(now)
                .uploadedBy(username)
                .build());
    }

    /**
     * 自分が上げた動画を新しい順に返す。上限も一緒に返す。
     *
     * @param username ログイン中の利用者のログイン名
     * @return 上限と上げた動画
     */
    public MyUploadListResponse list(String username) {
        return new MyUploadListResponse(
                maxFileGb * BYTES_PER_GB,
                userQuotaGb * BYTES_PER_GB,
                recordingRepository.sumFileSizeBytesByUploadedBy(username),
                recordingRepository.findByUploadedByOrderByStartedAtDesc(username).stream()
                        .map(MyUploadResponse::from)
                        .toList());
    }

    /**
     * 自分が上げた動画を消す。ファイルの削除と監査は {@link RecordingHistoryService#deleteRecording} に任せる。
     *
     * @param username ログイン中の利用者のログイン名
     * @param id       録画の主キー
     * @throws RecordingNotFoundException 無い場合、ほかの人の動画の場合（どちらも 404。あるかどうかを見せない）
     */
    public void delete(String username, Long id) {
        Recording recording = recordingHistoryService.findById(id);
        if (!username.equals(recording.getUploadedBy())) {
            throw new RecordingNotFoundException(id);
        }
        recordingHistoryService.deleteRecording(id);
    }

    /**
     * 前回の起動の受け取り中・詰め替え中のファイルを消す。{@code --force} で止めたときなどに残る。
     *
     * <p>行が無いので {@link RecordingReconciler} の対象にならず、ここで消さないとディスクに残り続ける。
     * 確認用の起動では消さない（{@link #schedulingEnabled} の JavaDoc 参照）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void deleteLeftovers() {
        Path directory = uploadsDirectory();
        if (!schedulingEnabled || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(VideoUploadService::isWorkFile).forEach(file -> {
                log.info("前回の起動のアップロードの途中のファイルを消します: {}", file);
                deleteQuietly(file);
            });
        } catch (IOException e) {
            log.warn("アップロードの途中のファイルを確かめられませんでした: {}", directory, e);
        }
    }

    /**
     * 受け取り中・詰め替え中のファイルかどうか。
     *
     * @param file 調べるファイル
     * @return どちらかなら {@code true}
     */
    private static boolean isWorkFile(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(RECEIVING_SUFFIX) || name.endsWith(REMUXING_SUFFIX);
    }

    /** @return 上げた動画の置き場所 */
    private Path uploadsDirectory() {
        return Path.of(monitorProperties.recording().directory()).resolve(UPLOADS_DIRECTORY);
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
            log.warn("アップロードのファイルを削除できませんでした: {}", file, e);
        }
    }

    /** @return 取り込めない動画を表す例外 */
    private static IllegalArgumentException unsupportedVideo() {
        return new IllegalArgumentException(UNSUPPORTED_MESSAGE);
    }

    /**
     * {@code ffprobe} で分かった、詰め替えに要る情報。
     *
     * @param videoCodec 1 つ目の映像の形式
     * @param audioCodec 1 つ目の音声の形式。音声が無ければ空文字
     */
    private record ProbedVideo(String videoCodec, String audioCodec) {
    }
}
