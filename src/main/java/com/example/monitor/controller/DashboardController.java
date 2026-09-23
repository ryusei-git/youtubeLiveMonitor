package com.example.monitor.controller;

import com.example.monitor.dto.DashboardResponse;
import com.example.monitor.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ダッシュボード表示用の集計値を返す REST API。
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

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
}
