package com.example.monitor.dto;

import java.time.Instant;

/**
 * 外部サービスから取ってきた動画 1 件（保存前）。
 *
 * <p>外部サービスの応答を保存処理から切り離し、IDとURLの取り違えを防ぐ。
 *
 * @param key          {@code YOUTUBE_<動画ID>}・{@code TWITCH_stream_<配信ID>}・
 *                     {@code TWITCH_video_<VOD の ID>} の形の主キー
 * @param title        動画のタイトル
 * @param watchUrl     視聴ページの URL
 * @param thumbnailUrl サムネイル画像の URL
 * @param publishedAt  公開された時刻。収集開始の境界より前かの判定にも使う
 */
public record OnlineVideoCandidate(String key, String title, String watchUrl,
                                   String thumbnailUrl, Instant publishedAt) {}
