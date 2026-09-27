package com.example.monitor.dto;

/**
 * 新人発掘の巡回の状態（Issue #488）。
 *
 * @param lastRunAt                  前回の巡回の開始日時（ISO 8601）。起動後に一度も動いていなければ {@code null}
 * @param nextRunAt                  次の定期の巡回の日時（ISO 8601）。定期の巡回が止まっていれば {@code null}
 * @param discoverySearchesUsedToday 発掘が今日使った検索の回数（太平洋時間の日付）
 * @param discoveryLimit             発掘の 1 日の検索の上限
 */
public record DiscoveryStatusResponse(String lastRunAt, String nextRunAt, int discoverySearchesUsedToday,
                                      int discoveryLimit) {}
