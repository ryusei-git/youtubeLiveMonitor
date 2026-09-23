package com.example.monitor.playground;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * YouTube Data API のお試し実行を受け付ける REST API。
 *
 * <p>パスは {@code /api/playground} 配下にまとめてある。既存の API
 * （{@code /api/channels} など）とは名前空間が重ならないため、
 * このコントローラーを削除しても既存の経路には影響しない。
 */
@RestController
@RequestMapping("/api/playground")
@RequiredArgsConstructor
public class ApiPlaygroundController {

    private final ApiPlaygroundService apiPlaygroundService;

    /**
     * 実行できる API の一覧と、現在のクォータ消費累計を返す。
     *
     * @return API 定義の一覧とクォータ累計
     */
    @GetMapping("/apis")
    public Map<String, Object> listApis() {
        List<ApiDefinition> apis = apiPlaygroundService.listApis();
        return Map.of(
                "apis", apis,
                "sessionQuotaTotal", apiPlaygroundService.sessionQuotaTotal());
    }

    /**
     * API を実行して、応答の JSON をそのまま返す。
     *
     * @param request 実行要求
     * @return 実行結果（失敗も本文で返す。{@link ApiExecutionResult} 参照）
     * @throws IllegalArgumentException 指定 API が無い、または必須項目が空の場合（400）
     */
    @PostMapping("/execute")
    public ApiExecutionResult execute(@RequestBody ApiExecutionRequest request) {
        return apiPlaygroundService.execute(request);
    }
}
