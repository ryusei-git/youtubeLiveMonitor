package com.example.monitor.dto;

/**
 * 分類ごとの録画件数。アーカイブ一覧の上の分類のボタンに使う。
 *
 * <p>分類の定義はコードの定数にあるので、画面に表示名を持たせず API で渡す
 * （定義を変えたときに、画面と食い違わないようにするため）。
 *
 * @param key   分類の key（URL と API の値）
 * @param label 画面に出す名前
 * @param count その分類の録画件数
 */
public record RecordingCategoryCountResponse(String key, String label, long count) {
}
