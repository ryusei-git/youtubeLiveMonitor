package com.example.monitor.controller;

import com.example.monitor.dto.DiscoveryAddRequest;
import com.example.monitor.dto.DiscoveryCandidateResponse;
import com.example.monitor.dto.DiscoveryDecisionRequest;
import com.example.monitor.dto.DiscoveryStatusResponse;
import com.example.monitor.service.CurrentAppUser;
import com.example.monitor.service.DiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 新人発掘の候補を見て「VTuber／ちがう」を判定し、手動で候補を足す API（Issue #488）。
 * {@code /api/my/**} はログインしていれば使える（{@code SecurityConfig}）。
 */
@RestController
@RequestMapping("/api/my/discover")
@RequiredArgsConstructor
public class MyDiscoverController {

    private final DiscoveryService discoveryService;
    private final CurrentAppUser currentAppUser;

    /**
     * 候補を見つけた新しい順に返す。
     *
     * @param status {@code CANDIDATE}（既定）か {@code VTUBER}
     * @return 候補
     */
    @GetMapping("/candidates")
    public List<DiscoveryCandidateResponse> list(@RequestParam(required = false) String status) {
        return discoveryService.list(status);
    }

    /**
     * 候補を判定する。判定した利用者を記録する。
     *
     * @param channelId チャンネル ID
     * @param request   判定
     * @return 判定した後の候補
     */
    @PutMapping("/candidates/{channelId}")
    public DiscoveryCandidateResponse decide(@PathVariable String channelId, @RequestBody DiscoveryDecisionRequest request) {
        return discoveryService.decide(channelId, request.status(), currentAppUser.require().getUsername());
    }

    /**
     * URL・ハンドル・チャンネル ID から候補を手動で登録する。
     *
     * @param request 入力
     * @return 登録した候補
     */
    @PostMapping("/candidates")
    public DiscoveryCandidateResponse add(@RequestBody DiscoveryAddRequest request) {
        return discoveryService.add(request.url());
    }

    /**
     * 巡回の状態を返す。
     *
     * @return 前回・次回の時刻と今日の検索の回数
     */
    @GetMapping("/status")
    public DiscoveryStatusResponse status() {
        return discoveryService.status();
    }

    /**
     * 候補・チャンネルが無いことを、共通の 500 処理へ渡さず 404 にする。
     *
     * @param exception 見つからなかったこと
     * @return 404 の応答
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(404).body(Map.of("error", exception.getMessage()));
    }
}
