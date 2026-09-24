package com.example.monitor.dto;

/**
 * ジャンルごとの録画件数。アーカイブ一覧のジャンル選択の選択肢に使う。
 *
 * <p>リポジトリの JPQL（コンストラクタ式）から直接組み立てる。{@code Object[]} で受けて
 * 詰め替えるより、列の順番や型を取り違える余地が無いため。
 *
 * @param genre ジャンル（タイトルの最初の {@code 【】} の中身）
 * @param count そのジャンルの録画件数
 */
public record RecordingGenreCountResponse(String genre, long count) {
}
