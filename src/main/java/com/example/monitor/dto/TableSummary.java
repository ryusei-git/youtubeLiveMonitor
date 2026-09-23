package com.example.monitor.dto;

/**
 * DB管理画面のテーブル選択肢 1 件分。
 *
 * @param tableName テーブル名（DB 上の実際の表記）。API 呼び出し時はこちらを使う
 * @param label     画面表示用の論理名（日本語）。対応表に無いテーブルは {@link #tableName} と同じ値になる
 */
public record TableSummary(String tableName, String label) {}
