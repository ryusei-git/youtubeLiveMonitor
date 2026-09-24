package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.DashboardResponse;
import com.example.monitor.dto.DashboardResponse.DetectionFailureSummary;
import com.example.monitor.dto.DashboardResponse.LiveChannelSummary;
import com.example.monitor.dto.RecordingFailureResponse;
import com.example.monitor.dto.StorageUsageResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.DirectorySizeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * ダッシュボードに表示する集計値を組み立てる。
 *
 * <p>配信中かどうかは各チャンネルに保存された「直近の監視結果」を読むだけで、
 * ここから YouTube へ問い合わせることはしない。画面を開くたびに外部通信が走るのを避けるため、
 * 実際の観測は監視ループ（{@link com.example.monitor.scheduler.LiveStreamPollingScheduler}）に任せている。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 「最近の通知件数」として集計する時間の幅。 */
    private static final int RECENT_NOTIFICATION_WINDOW_HOURS = 24;

    /**
     * 何回連続で判定に失敗したらダッシュボードに警告として出すか。
     *
     * <p>1 回だけの失敗は一時的な通信エラーでも起こるため、ここを 1 にすると警告が
     * 頻繁に出て無視されるようになる。連続して失敗している＝一過性ではない、という線引き。
     */
    private static final int DETECTION_FAILURE_ALERT_THRESHOLD = 2;

    /** 「直近の録画失敗」として出す期間。古い失敗まで並べると今の問題が埋もれるため。 */
    private static final int RECENT_RECORDING_FAILURE_WINDOW_DAYS = 7;

    /** ログの置き場所。{@code logback-spring.xml} と {@code bin/service.sh} が決め打ちしているのに合わせる。 */
    private static final String LOG_DIRECTORY = "logs";

    /** H2 の DB ファイルの置き場所。{@code application.yml} の {@code jdbc:h2:file:./data/...} に合わせる。 */
    private static final String DATABASE_DIRECTORY = "data";

    private final MonitoredChannelRepository monitoredChannelRepository;
    private final NotificationHistoryService notificationHistoryService;
    private final RecordingHistoryService recordingHistoryService;
    private final UptimeTracker uptimeTracker;
    private final RecordingRepository recordingRepository;
    private final MonitorProperties monitorProperties;

    /**
     * 現時点のダッシュボード表示内容を組み立てて返す。
     *
     * @return 集計結果
     */
    public DashboardResponse getSnapshot() {
        List<MonitoredChannel> allChannels = monitoredChannelRepository.findAll();

        List<LiveChannelSummary> liveChannels = allChannels.stream()
                .filter(MonitoredChannel::isCurrentlyLive)
                .map(channel -> new LiveChannelSummary(
                        channel.getYoutubeChannelId(),
                        channel.getChannelName(),
                        channel.getCurrentLiveVideoId()))
                .toList();

        // 連続失敗が多い順に並べる（最も壊れている疑いが強いものを先に見せるため）
        List<DetectionFailureSummary> detectionFailingChannels = allChannels.stream()
                .filter(channel -> channel.getConsecutiveDetectionFailures() >= DETECTION_FAILURE_ALERT_THRESHOLD)
                .sorted(Comparator.comparingInt(MonitoredChannel::getConsecutiveDetectionFailures).reversed())
                .map(channel -> new DetectionFailureSummary(
                        channel.getYoutubeChannelId(),
                        channel.getChannelName(),
                        channel.getConsecutiveDetectionFailures(),
                        channel.getLastDetectionSuccessAt()))
                .toList();

        LocalDateTime since = LocalDateTime.now().minusHours(RECENT_NOTIFICATION_WINDOW_HOURS);

        return new DashboardResponse(
                allChannels.size(),
                liveChannels.size(),
                liveChannels,
                notificationHistoryService.countSince(since),
                notificationHistoryService.countFailuresSince(since),
                detectionFailingChannels,
                recordingHistoryService.countByStatus(),
                uptimeTracker.getStartedAt(),
                uptimeTracker.getUptimeSeconds()
        );
    }

    /**
     * このサービスを構成するファイルの容量を、実ファイルを走査して求める。
     *
     * <p>DB に記録されたサイズを合計しないのは、失敗した録画の断片ファイルのように
     * DB に載らないままディスクを使っているものがあるため（{@code docs/pitfalls.md} 参照）。
     *
     * @return 項目ごとの容量と合計。項目は容量の大きい順
     */
    public StorageUsageResponse getStorageUsage() {
        List<StorageUsageResponse.Item> items = List.of(
                        item("recordings", "録画", Path.of(monitorProperties.recording().directory())),
                        item("logs", "ログ", Path.of(LOG_DIRECTORY)),
                        item("application", "アプリ本体", applicationPath()),
                        item("database", "データベース", Path.of(DATABASE_DIRECTORY)))
                .stream()
                .sorted(Comparator.comparingLong(StorageUsageResponse.Item::bytes).reversed())
                .toList();
        long totalBytes = items.stream().mapToLong(StorageUsageResponse.Item::bytes).sum();
        return new StorageUsageResponse(totalBytes, items);
    }

    /**
     * 開始が直近の、録画に失敗した配信を新しい順に返す。
     *
     * @return 録画に失敗した配信（最大 20 件）
     */
    public List<RecordingFailureResponse> getRecentRecordingFailures() {
        LocalDateTime since = LocalDateTime.now().minusDays(RECENT_RECORDING_FAILURE_WINDOW_DAYS);
        return recordingRepository
                .findTop20ByStatusAndStartedAtGreaterThanEqualOrderByStartedAtDesc(RecordingStatus.FAILED, since)
                .stream()
                .map(RecordingFailureResponse::from)
                .toList();
    }

    private StorageUsageResponse.Item item(String key, String label, Path path) {
        return new StorageUsageResponse.Item(key, label, displayPath(path), DirectorySizeUtils.sizeOf(path));
    }

    /**
     * 実行中のアプリ本体の場所を求める。
     *
     * <p>jar 名を決め打ちしないのは、バージョンが上がると名前が変わるため。
     * {@link ApplicationHome} は jar から動いていればその jar、Gradle や IDE から
     * クラスディレクトリで動いていればそのディレクトリを返す（Spring Boot の入れ子 jar の URL も解釈できる）。
     *
     * @return アプリ本体のファイルまたはディレクトリ
     */
    private Path applicationPath() {
        File source = new ApplicationHome(DashboardService.class).getSource();
        return source != null ? source.toPath() : Path.of("build/libs");
    }

    /**
     * 画面に出すパスを作る。作業ディレクトリの中なら相対パスにして短くし、外なら絶対パスのままにする。
     */
    private static String displayPath(Path path) {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(workingDirectory)
                ? workingDirectory.relativize(absolute).toString()
                : absolute.toString();
    }
}
