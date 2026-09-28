package com.example.monitor.controller;

import com.example.monitor.dto.TableDataResponse;
import com.example.monitor.dto.TableSummary;
import com.example.monitor.service.DatabaseTableService;
import com.example.monitor.util.PageRequestUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
 * そのため管理者（ADMIN）だけが使える（{@code SecurityConfig} が {@code /api/admin/tables/**} を ADMIN に閉じている）。
 * この制限を緩めないこと。秘密や証跡を持つテーブルは {@link DatabaseTableService} が対象から外している。
 */
@RestController
@RequestMapping("/api/admin/tables")
@RequiredArgsConstructor
public class DatabaseTableController {

    /**
     * 1 ページの行数の上限。画面は 20 行ずつ読む。上限が無いと、URL の {@code size} を大きくしただけで
     * 表を丸ごと読んで JSON にしてしまう（#184）。
     */
    private static final int MAX_PAGE_SIZE = 200;

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
     * @param size      1 ページあたりの行数（1〜{@value #MAX_PAGE_SIZE}）
     * @return テーブルの内容とカラム構成
     * @throws IllegalArgumentException ページ番号・行数が範囲外の場合（400）
     */
    @GetMapping("/{tableName}")
    public TableDataResponse getTableData(
            @PathVariable String tableName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageRequest = PageRequestUtils.bounded(page, size, MAX_PAGE_SIZE);
        return databaseTableService.getTableData(tableName, pageRequest.getPageNumber(), pageRequest.getPageSize());
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
