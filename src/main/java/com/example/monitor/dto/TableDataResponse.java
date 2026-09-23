package com.example.monitor.dto;

import java.util.List;
import java.util.Map;

/**
 * DB テーブル 1 つ分の内容を表形式で返すレスポンス。
 *
 * <p>特定のテーブル専用ではなく任意のテーブルを扱うため、行は
 * 「カラム名 → 値」の {@link Map} として持つ。表の列順を画面側で再現できるよう、
 * カラム名の並びは {@link #columns} に別途持たせている。
 *
 * @param tableName         テーブル名（DB 上の実際の表記）
 * @param tableLabel        テーブルの論理名（日本語）。対応表に無いテーブルは {@link #tableName} と同じ値
 * @param columns           カラム名の一覧。表示順もこの並びに従う
 * @param columnLabels      カラム名（DB 上の物理名） → 論理名（日本語）の対応。
 *                          対応表に無いカラムは物理名と同じ値になる（画面側が必ず表示文字列を得られるように）
 * @param primaryKeyColumn  主キーのカラム名。行の更新時にこの値で行を特定する。主キーがない表では {@code null}
 * @param rows              1 行を「カラム名 → 値」で表したもののリスト
 * @param totalElements     ページングを無視した全体の行数
 * @param page              現在のページ番号（0 始まり）
 * @param size              1 ページあたりの行数
 */
public record TableDataResponse(
        String tableName,
        String tableLabel,
        List<String> columns,
        Map<String, String> columnLabels,
        String primaryKeyColumn,
        List<Map<String, Object>> rows,
        long totalElements,
        int page,
        int size
) {}
