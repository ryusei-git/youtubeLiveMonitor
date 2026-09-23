package com.example.monitor.playground;

import java.util.Map;

/**
 * お試し実行の要求。
 *
 * @param apiId      実行する API の識別子（{@link ApiDefinition#id()}）
 * @param parameters 入力された値。キーは {@link ApiParameter#name()}
 */
public record ApiExecutionRequest(
        String apiId,
        Map<String, String> parameters
) {}
