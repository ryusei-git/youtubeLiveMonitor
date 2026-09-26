package com.example.monitor.scheduler;

import com.example.monitor.service.SoundDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 録画が終わった録画に、耳キスの検出を掛ける見回り（Issue #469）。
 *
 * <p>配信の巡回（{@link LiveStreamPollingScheduler}）には入れず、自分の周期で動く。検出は 1 本で数十秒 CPU を使い、
 * 巡回に入れると配信の検知・通知・録画の開始を遅らせるため。多重に走らせない仕組みと仮想スレッドは
 * {@link SoundDetectionService#startPending()} にある（今すぐ検出など、ほかの入口と排他を共有するため）。
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
public class SoundDetectionScheduler {

    private final SoundDetectionService soundDetectionService;

    /**
     * 見回りを動かすか。既定は {@code monitor.scheduling.enabled} と同じ（理由は {@code application.yml} の
     * {@code monitor.sound-detection}）。
     */
    @Value("${monitor.sound-detection.enabled:${monitor.scheduling.enabled:true}}")
    private boolean enabled = true;

    /** 見回りを 1 回始める。前の回がまだ終わっていなければ見送られる。 */
    @Scheduled(fixedDelayString = "${monitor.sound-detection.interval:PT10M}")
    public void schedule() {
        if (enabled) {
            soundDetectionService.startPending();
        }
    }
}
