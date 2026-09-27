package com.example.monitor.controller;

import com.example.monitor.dto.YouTubeSearchRequest;
import com.example.monitor.dto.YouTubeSearchResponse;
import com.example.monitor.service.CurrentAppUser;
import com.example.monitor.service.SearchQuotaStatus;
import com.example.monitor.service.YouTubeSearchBudget;
import com.example.monitor.service.YouTubeSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 利用者の画面の YouTube 検索と、視聴画面の動画の詳細の API。
 *
 * <p>検索の回数は利用者ごとに数えるので、誰の検索かはログイン中の利用者から決め、
 * リクエストから指定させない。
 *
 * <p>Web の画面からしか使わないため {@code @Profile("!cli")} を付ける
 * （{@code docs/pitfalls.md}「cli プロファイルで作られない Bean に依存するコントローラー…」）。
 */
@RestController
@Profile("!cli")
@RequiredArgsConstructor
public class MySearchController {

    private final YouTubeSearchService searchService;
    private final YouTubeSearchBudget searchBudget;
    private final CurrentAppUser currentAppUser;

    /**
     * YouTube の動画を検索する。
     *
     * @param request 公式の条件とこのサービスの条件
     * @return 結果と今日の残り
     */
    @GetMapping("/api/my/search")
    public YouTubeSearchResponse search(@Valid @ModelAttribute YouTubeSearchRequest request) {
        return searchService.search(request, currentAppUser.require().getUsername());
    }

    /**
     * 検索の今日の残りを返す。回数は使わない。
     *
     * @return 対話の残り・自分の残り・戻る時刻
     */
    @GetMapping("/api/my/search/quota")
    public SearchQuotaStatus quota() {
        return searchBudget.status(currentAppUser.require().getUsername());
    }

    /**
     * 視聴画面のために動画 1 件の詳細を返す。検索の回数は使わない。
     *
     * @param videoId 動画 ID
     * @return 詳細。無ければ 404
     */
    @GetMapping("/api/my/youtube/videos/{videoId}")
    public ResponseEntity<?> video(@PathVariable String videoId) {
        return searchService.findVideo(videoId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "動画が見つかりません")));
    }
}
