package com.example.monitor.controller;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.service.PollingStatusTracker;
import com.example.monitor.service.UptimeTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 巡回が回っているかを返す API（#243）。外の見張り（cron・{@code bin/service.sh status}）が叩く。
 *
 * <p>巡回が固まる・例外で抜け続けても、ポートは開いたままなので外からは区別できない。
 * そこで {@link PollingStatusTracker} の「最後に巡回が最後まで回った時刻」の古さで判定する。
 * 見るのは「巡回が回っているか」だけで、YouTube に届くか（検知の失敗）はダッシュボードの警告が受け持つ。
 *
 * <p><b>ログイン無しで読める</b>ので、返すのは状態と経過秒だけにする。チャンネル名・件数・パスなどは入れない。
 * ここから巡回を起動しない（起動の経路を増やすと排他を通す必要が出る。{@code docs/pitfalls.md}）。
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    /**
     * 巡回間隔の何倍まで待つか。{@code fixedDelay} なので成功の間隔は「間隔＋1 巡の所要時間」になる。
     * 1 回の遅れで {@code STALE} にしないため、間隔ちょうどではなく余裕を持たせる。
     */
    private static final int STALE_INTERVAL_MULTIPLIER = 3;

    private final PollingStatusTracker pollingStatusTracker;
    private final UptimeTracker uptimeTracker;
    private final MonitorProperties monitorProperties;

    /** 確認用の起動（{@code bin/preview.sh}）はわざと巡回しないので、止まっていても異常としない。 */
    @Value("${monitor.scheduling.enabled:true}")
    private boolean schedulingEnabled = true;

    /**
     * 巡回の状態を返す。
     *
     * <p>{@code DISABLED}・{@code STARTING}・{@code UP} は 200、{@code STALE} は 503。
     * 見張りが {@code curl -f} の成否だけで判定できるように、止まっているときは HTTP の状態で返す。
     * まだ 1 巡もしていないときは起動時刻を基準にし、起動直後から固まっている場合も {@code STALE} にする。
     *
     * @return 状態と、最後に巡回が最後まで回ってからの経過秒（まだ 1 巡もしていなければ {@code null}）
     */
    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        LocalDateTime lastSucceededAt = pollingStatusTracker.lastSucceededAt();
        LocalDateTime now = LocalDateTime.now();
        Long secondsSinceLastPoll = lastSucceededAt == null
                ? null : Duration.between(lastSucceededAt, now).getSeconds();
        if (!schedulingEnabled) {
            return ResponseEntity.ok(new HealthResponse("DISABLED", secondsSinceLastPoll));
        }
        LocalDateTime base = lastSucceededAt != null ? lastSucceededAt : uptimeTracker.getStartedAt();
        long threshold = (long) STALE_INTERVAL_MULTIPLIER * monitorProperties.youtube().intervalSeconds();
        if (Duration.between(base, now).getSeconds() > threshold) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthResponse("STALE", secondsSinceLastPoll));
        }
        String status = lastSucceededAt == null ? "STARTING" : "UP";
        return ResponseEntity.ok(new HealthResponse(status, secondsSinceLastPoll));
    }

    /**
     * ヘルスの応答。ログイン無しで読めるので、この 2 項目より増やさない。
     *
     * @param status               {@code UP}・{@code STARTING}・{@code DISABLED}・{@code STALE} のいずれか
     * @param secondsSinceLastPoll 最後に巡回が最後まで回ってからの経過秒。まだ 1 巡もしていなければ {@code null}
     */
    public record HealthResponse(String status, Long secondsSinceLastPoll) {
    }
}
