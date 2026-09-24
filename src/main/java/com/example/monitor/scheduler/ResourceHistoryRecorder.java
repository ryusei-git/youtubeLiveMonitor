package com.example.monitor.scheduler;

import com.example.monitor.service.ResourceMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * リソースの推移を 1 分ごとに記録する。
 *
 * <p>{@code fixedRate} にするのは、記録の間隔を一定に保ち、折れ線の横軸と CPU 使用率の平均の窓を
 * そろえるため。起動直後にも 1 回動く。CPU 使用率は 2 回の記録の差から出すので、値が出るのは
 * 2 回目の記録（起動の 1 分後）から。
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
public class ResourceHistoryRecorder {

    private final ResourceMonitorService resourceMonitorService;

    /** 1 分ごとに今の値を推移へ加える。 */
    @Scheduled(fixedRate = 60000)
    public void record() {
        resourceMonitorService.record();
    }
}
