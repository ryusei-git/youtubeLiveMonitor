package com.example.monitor.controller;

import com.example.monitor.dto.TableDataResponse;
import com.example.monitor.dto.TableSummary;
import com.example.monitor.service.DatabaseTableService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * DB のテーブルを表形式で閲覧・編集する REST API。H2 標準コンソールの代替。
 *
 * <p><b>任意のテーブルの任意の行を書き換えられる。</b>
 * 認証を持たない個人用ローカルツールという前提の機能なので、
 * 外部からアクセスできる環境に置く場合は必ずアクセス制御を追加すること。
 */
@RestController
@RequestMapping("/api/admin/tables")
@RequiredArgsConstructor
public class DatabaseTableController {

    private final DatabaseTableService databaseTableService;

    /**
     * 選択できるテーブルの一覧を、論理名（日本語）付きで返す。
     *
     * @return テーブル名と論理名の一覧
     */
    @GetMapping
    public List<TableSummary> listTables() {
        return databaseTableService.listTableSummaries();
    }

    /**
     * 指定テーブルの内容をページ単位で返す。
     *
     * @param tableName テーブル名（大文字小文字は区別しない）
     * @param page      ページ番号（0 始まり）
     * @param size      1 ページあたりの行数
     * @return テーブルの内容とカラム構成
     */
    @GetMapping("/{tableName}")
    public TableDataResponse getTableData(
            @PathVariable String tableName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return databaseTableService.getTableData(tableName, page, size);
    }

    /**
     * 主キーで特定した 1 行を更新する。
     *
     * <p>リクエスト本文には変更したいカラムだけを含めればよい。
     * 実在しないカラムや主キーの変更指示は無視される。
     *
     * @param tableName テーブル名
     * @param pkValue   更新する行の主キー値
     * @param changes   「カラム名 → 新しい値」の対応
     */
    @PutMapping("/{tableName}/{pkValue}")
    public void updateRow(
            @PathVariable String tableName,
            @PathVariable String pkValue,
            @RequestBody Map<String, Object> changes) {
        databaseTableService.updateRow(tableName, pkValue, changes);
    }
}
