package com.example.monitor.controller;

import com.example.monitor.service.DiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理者が新人発掘の巡回を今すぐ 1 回動かす API（試験用。検索の回数を使う。Issue #488）。
 * {@code /api/discover/**} は管理者だけ（{@code SecurityConfig}）。
 */
@RestController
@RequestMapping("/api/discover")
@RequiredArgsConstructor
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    /**
     * 巡回を 1 回、終わるまで待って結果を返す。定期の巡回と同じ排他を通る。
     *
     * @return 使った検索の回数と新しい候補の数。別の巡回が動いていれば 409
     */
    @PostMapping("/run")
    public ResponseEntity<?> run() {
        return discoveryService.run()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409).body(Map.of("error", "発掘の巡回が実行中です")));
    }
}
