/**
 * 定期実行の入口（配信の巡回・動画の収集・リソースの記録）。
 *
 * <p>巡回の中では、巡回の開始時に読み込んだ {@code MonitoredChannel} を {@code save(entity)} で書き戻さず、
 * {@code MonitoredChannelRepository} の列を絞った UPDATE を使う。巡回中に画面から変えられた設定を、
 * 巡回が読み込んだ古い値で上書きしないため（{@code docs/pitfalls.md}「監視ループから {@code save(entity)} を呼ばない」）。
 */
package com.example.monitor.scheduler;
