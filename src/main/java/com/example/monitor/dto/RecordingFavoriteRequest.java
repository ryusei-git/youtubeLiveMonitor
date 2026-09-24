package com.example.monitor.dto;

/**
 * 録画のお気に入りを切り替えるリクエスト。
 *
 * @param favorite お気に入りにするなら {@code true}、外すなら {@code false}
 */
public record RecordingFavoriteRequest(boolean favorite) {}
