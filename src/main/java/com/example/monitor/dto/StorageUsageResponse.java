package com.example.monitor.dto;

import java.util.List;

/**
 * このサービスを構成するファイル（録画・ログ・アプリ本体・データベース）の容量。
 *
 * <p>録画だけを見る {@link DiskUsageResponse} とは別に持つ。あちらはチャンネル別の内訳と
 * 削除済みチャンネルの判定を担っており、置き場所の違う種類のファイルを混ぜると意味が崩れるため。
 *
 * @param totalBytes 全項目の合計（バイト）
 * @param items      項目ごとの容量。容量の大きい順に並ぶ
 */
public record StorageUsageResponse(long totalBytes, List<Item> items) {

    /**
     * 1 項目分の容量。
     *
     * @param key   画面が項目を見分けるための固定の識別子（表示名は変わりうるため）
     * @param label 表示名
     * @param path  作業ディレクトリからの相対パス（外にあれば絶対パス）。画面にそのまま出す
     * @param bytes 実ファイルを走査して求めた容量（バイト）。存在しなければ 0
     */
    public record Item(String key, String label, String path, long bytes) {}
}
