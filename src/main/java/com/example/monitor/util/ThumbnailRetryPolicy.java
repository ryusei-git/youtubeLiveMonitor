package com.example.monitor.util;

import java.time.Duration;
import java.time.Instant;

/** 動画ごとの再試行時刻を分散し、失敗画像が後続動画を塞がないようにする。 */
public final class ThumbnailRetryPolicy {
    /** 障害が続く画像への通信を無制限に増やさないための上限。 */
    public static final int MAX_ATTEMPTS = 5;
    private static final Duration BASE_DELAY = Duration.ofMinutes(10);
    private static final int JITTER_SECONDS = 600;

    private ThumbnailRetryPolicy() {}

    /** 10・20・40・80分に動画ID由来の0〜9分59秒を加え、同時再試行を避ける。
     * @param videoId 再試行対象
     * @param attempts 今回の失敗を含む回数
     * @param now 失敗が確定した時刻
     * @return 次回試行時刻。上限到達なら null
     */
    public static Instant nextAttemptAt(String videoId, int attempts, Instant now) {
        if (attempts >= MAX_ATTEMPTS) return null;
        long multiplier = 1L << Math.max(0, attempts - 1);
        return now.plus(BASE_DELAY.multipliedBy(multiplier))
                .plusSeconds(Math.floorMod(videoId.hashCode(), JITTER_SECONDS));
    }

    /** 上限到達を一覧でも区別できるよう、保存した回数だけから判定する。 */
    public static boolean exhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }
}
