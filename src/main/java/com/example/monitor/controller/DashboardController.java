package com.example.monitor.controller;

import com.example.monitor.dto.DashboardResponse;
import com.example.monitor.dto.RecordingFailureResponse;
import com.example.monitor.dto.ResourceHistoryPoint;
import com.example.monitor.dto.ResourceSnapshotResponse;
import com.example.monitor.dto.StorageForecastResponse;
import com.example.monitor.dto.StorageUsageResponse;
import com.example.monitor.service.DashboardService;
import com.example.monitor.service.ResourceMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ダッシュボード表示用の集計値を返す REST API。
 *
 * <p>CLI では作らない。リソース計測（{@link ResourceMonitorService}）が CLI では作られず、
 * 依存したままだと CLI が丸ごと起動できなくなるため。
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final ResourceMonitorService resourceMonitorService;

    /**
     * 現時点の集計値を返す。
     *
     * <p>返す配信状態は監視ループが最後に観測した結果であり、
     * このリクエストの時点で YouTube に問い合わせているわけではない。
     * したがって最大で監視間隔ぶんの遅れがある。
     *
     * @return ダッシュボードの表示内容
     */
    @GetMapping
    public DashboardResponse getDashboard() {
        return dashboardService.getSnapshot();
    }

    /**
     * このサービスを構成するファイル（録画・ログ・アプリ本体・データベース）の容量を返す。
     *
     * <p>{@link #getDashboard()} に含めないのは、ディレクトリの走査が録画の量に比例して重くなり、
     * 定期的に読み直すダッシュボード本体の応答まで遅くしないため。
     *
     * @return 項目ごとの容量と合計
     */
    @GetMapping("/storage")
    public StorageUsageResponse getStorage() {
        return dashboardService.getStorageUsage();
    }

    /**
     * 録画フォルダーの空きが、新しい録画を始めるしきい値を割るまでの見込みを返す。
     *
     * <p>{@link #getStorage()} と分けているのは、あちらがディレクトリの走査で重いのに対し、
     * こちらは DB の集計とボリュームの空き容量を読むだけで軽く、別々に読み直せるようにするため。
     *
     * @return 見込み
     */
    @GetMapping("/storage-forecast")
    public StorageForecastResponse getStorageForecast() {
        return dashboardService.getStorageForecast();
    }

    /**
     * 直近 7 日に録画に失敗した配信を新しい順に返す（最大 20 件）。
     *
     * @return 録画に失敗した配信。無ければ空の配列
     */
    @GetMapping("/recording-failures")
    public List<RecordingFailureResponse> getRecordingFailures() {
        return dashboardService.getRecentRecordingFailures();
    }

    /**
     * 端末全体とこのサービスが今使っているリソースと、目安を超えた項目を返す。
     *
     * <p>値は直前の 1 分ごとの記録のもの（最大 1 分古い）。リクエストのたびに全プロセスを列挙し直さないため。
     * CPU 使用率とネットワーク量は、その記録と 1 つ前の記録の間の平均。
     *
     * @return 今のリソース
     */
    @GetMapping("/resources")
    public ResourceSnapshotResponse getResources() {
        return resourceMonitorService.snapshot();
    }

    /**
     * 直近 24 時間のリソースの推移を古い順に返す（1 分ごと）。
     *
     * @return 1 分ごとの記録。再起動するとそこから記録し直す
     */
    @GetMapping("/resources/history")
    public List<ResourceHistoryPoint> getResourceHistory() {
        return resourceMonitorService.history();
    }
}
