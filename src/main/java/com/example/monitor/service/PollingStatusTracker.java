package com.example.monitor.service;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/** JVMの稼働と巡回の停止を混同しないよう、巡回の境界を別に記録する。 */
@Component
public class PollingStatusTracker {
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private boolean running;
    private boolean successful;

    /** 排他取得後だけ呼び、重複要求で時刻を上書きしない。 */
    public synchronized void start() { startedAt = LocalDateTime.now(); running = true; }
    /** 例外終了も記録し、実行中のままに見せない。 */
    public synchronized void finish(boolean success) {
        finishedAt = LocalDateTime.now(); running = false; successful = success;
    }
    /** 表示側に整合した一組の状態を渡す。 */
    public synchronized Snapshot snapshot() { return new Snapshot(startedAt, finishedAt, running, successful); }
    /** 最終終了時刻は成功・失敗を併せて解釈する。 */
    public record Snapshot(LocalDateTime startedAt, LocalDateTime finishedAt, boolean running, boolean successful) {}
}
