package com.example.monitor.controller;

import com.example.monitor.dto.ManualCheckResponse;
import com.example.monitor.exception.MonitoringDisabledException;
import com.example.monitor.exception.MonitoringInProgressException;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.scheduler.LiveStreamPollingScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 監視サイクルそのものを操作する REST API。
 *
 * <p>チャンネルや履歴といった「データ」ではなく監視の動作を扱うため、専用のパスに分けている。
 *
 * <p><b>{@code cli} プロファイルでは作らない。</b>依存している
 * {@link LiveStreamPollingScheduler} が {@code @Profile("!cli")} で CLI モードでは
 * 生成されないため、この指定が無いと <b>CLI が「Bean が見つからない」で起動できなくなる</b>
 * （実際に発生した）。CLI モードは Web サーバーを起動しないので、そもそも
 * このコントローラーは不要。
 */
@RestController
@RequestMapping("/api/monitor")
@Profile("!cli")
@RequiredArgsConstructor
public class MonitoringController {

    private final LiveStreamPollingScheduler liveStreamPollingScheduler;
    private final MonitoredChannelRepository monitoredChannelRepository;

    /**
     * 監視を行う起動か。確認用の起動（{@code bin/preview.sh} など）では {@code false} になる。
     *
     * <p>{@code false} のとき {@link LiveStreamPollingScheduler#pollNow()} は巡回せずに {@code false} を返すが、
     * その値は「既に巡回中」と見分けがつかない。待っても直らない状態を「実行中です。お待ちください」と
     * 伝えないよう、ここで先に見て別の理由を返す。
     * 初期値を {@code true} にしているのは、Spring を通さずに組み立てるテストでも今までどおり動かすため。
     */
    @Value("${monitor.scheduling.enabled:true}")
    private boolean schedulingEnabled = true;

    /**
     * 次の定期実行を待たずに、その場で全チャンネルを 1 巡する。
     *
     * <p>巡回が終わるまで応答を返さない（非同期にしない）。呼び出し側が
     * 「チェックが終わったので画面を読み直す」という判断をそのままできるようにするため。
     * ただしチャンネル 1 件あたり最大 10 秒（{@code LiveStreamDetector} の応答待ち上限）
     * かかりうるので、登録数が増えると応答も遅くなる。
     *
     * @return 巡回したチャンネル数
     * @throws MonitoringInProgressException 既に巡回中の場合（409 Conflict）
     * @throws MonitoringDisabledException 監視を止めた起動（確認用の起動）の場合（409 Conflict）
     */
    @PostMapping("/check")
    public ManualCheckResponse checkNow() {
        if (!schedulingEnabled) {
            throw new MonitoringDisabledException();
        }
        long channelCount = monitoredChannelRepository.count();

        if (!liveStreamPollingScheduler.pollNow()) {
            throw new MonitoringInProgressException();
        }
        return new ManualCheckResponse(channelCount);
    }
}
