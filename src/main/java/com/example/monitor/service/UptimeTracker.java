package com.example.monitor.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * アプリケーションの起動時刻を保持し、稼働時間を提供する。
 *
 * <p>Bean が生成された時刻をそのまま起動時刻として扱う。
 * ダッシュボードで「いつから動いているか」を表示するためだけの用途。
 */
@Component
public class UptimeTracker {

    /** Bean 生成時刻。アプリの起動時刻とみなす。 */
    private final LocalDateTime startedAt = LocalDateTime.now();

    /**
     * アプリが起動した時刻を返す。
     *
     * @return 起動時刻
     */
    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    /**
     * 起動してからの経過秒数を返す。
     *
     * @return 稼働秒数
     */
    public long getUptimeSeconds() {
        return Duration.between(startedAt, LocalDateTime.now()).getSeconds();
    }
}
