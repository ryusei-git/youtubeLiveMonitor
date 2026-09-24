package com.example.monitor.dto;

/**
 * 録画の視聴済みを切り替えるリクエスト。
 *
 * @param watched 視聴済みにするなら {@code true}、未視聴に戻すなら {@code false}
 */
public record RecordingWatchedRequest(boolean watched) {}
