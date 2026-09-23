package com.example.monitor.dto;

/**
 * チャンネルの録画タイトルフィルターを更新するリクエスト。
 *
 * @param titleKeywords 絞り込みキーワード（カンマ区切り、いずれか1つでも配信タイトルに
 *                      含まれていれば録画対象）。空文字または {@code null} で絞り込み解除
 */
public record RecordTitleFilterRequest(String titleKeywords) {}
