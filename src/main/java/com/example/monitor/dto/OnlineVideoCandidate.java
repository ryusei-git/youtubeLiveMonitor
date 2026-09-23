package com.example.monitor.dto;

import java.time.Instant;

/** 外部サービスの応答を保存処理から切り離し、IDとURLの取り違えを防ぐ。 */
public record OnlineVideoCandidate(String key, String title, String watchUrl,
                                   String thumbnailUrl, Instant publishedAt) {}
