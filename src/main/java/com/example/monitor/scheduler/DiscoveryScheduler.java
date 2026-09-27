package com.example.monitor.scheduler;

import com.example.monitor.service.DiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 新人発掘の定期の巡回（1 日 3 回）と、30 日の決まりの見回り（1 日 1 回）を起こす（Issue #488）。
 *
 * <p>配信の巡回（{@link LiveStreamPollingScheduler}）とは別のスケジュールにする。発掘は検索の回数を使うので、
 * 監視ループに入れない（{@code docs/pitfalls.md}「クォータを消費する API を監視ループに入れない」）。
 * 処理は仮想スレッドへ逃がす。{@code @Scheduled} のスレッドは配信の巡回などと共有しているため
 * （{@link OnlineVideoCollector} と同じ）。多重に走らせない仕組みは {@link DiscoveryService} にある。
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
@Slf4j
public class DiscoveryScheduler {

    private final DiscoveryService discoveryService;

    /** 既定は {@code monitor.scheduling.enabled} と同じ（確認用の起動では検索の回数を使わない）。 */
    @Value("${monitor.discovery.enabled:true}")
    private boolean enabled = true;

    /** 検索語を順に検索する。 */
    @Scheduled(cron = "${monitor.discovery.cron:-}", zone = DiscoveryService.ZONE)
    public void run() {
        if (!enabled) return;
        Thread.startVirtualThread(() -> {
            try {
                if (discoveryService.run().isEmpty()) log.info("前の発掘の巡回がまだ動いているので見送ります");
            } catch (RuntimeException e) {
                log.error("発掘の巡回に失敗しました。次の回で再試行します", e);
            }
        });
    }

    /** 30 日の決まりの見回りをする。 */
    @Scheduled(cron = "${monitor.discovery.sweep-cron:-}", zone = DiscoveryService.ZONE)
    public void sweep() {
        if (!enabled) return;
        Thread.startVirtualThread(() -> {
            try {
                discoveryService.sweep();
            } catch (RuntimeException e) {
                log.error("発掘の見回りに失敗しました。次の回で再試行します", e);
            }
        });
    }
}
