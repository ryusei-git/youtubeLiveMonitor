package com.example.monitor.service;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 巡回が最後に最後まで回った時刻を JVM の中に持つ。{@code GET /api/health} の判定の基準。
 *
 * <p>ポートが開いていることと巡回が回っていることは別。巡回が固まる・例外で抜け続けると、
 * 画面も API も応答したまま、この時刻だけが古くなる。
 *
 * <p>開始時刻や「実行中」の印は持たない。固まった巡回は終わらないので、
 * 終わった時刻の古さだけで検出できる。
 */
@Component
public class PollingStatusTracker {

    /** 巡回のスレッドが書き、ヘルスの要求のスレッドが読むので {@code volatile} にする。 */
    private volatile LocalDateTime lastSucceededAt;

    /** 巡回が最後まで回ったときに呼ぶ。例外で抜けた巡回を成功と数えないため、{@code finally} からは呼ばない。 */
    public void recordSuccess() {
        lastSucceededAt = LocalDateTime.now();
    }

    /**
     * 巡回が最後に最後まで回った時刻を返す。
     *
     * @return 最後に成功した時刻。起動してからまだ 1 巡もしていなければ {@code null}
     */
    public LocalDateTime lastSucceededAt() {
        return lastSucceededAt;
    }
}
