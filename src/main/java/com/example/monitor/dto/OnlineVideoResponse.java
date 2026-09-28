package com.example.monitor.dto;

import java.time.Instant;

/**
 * 動画ライブラリの 1 件を画面へ返す形。
 *
 * <p>外部動画の視聴に必要な情報だけを返し、画像本体は専用の経路で配信する。
 *
 * @param id                      動画の主キー。{@code YOUTUBE_<動画ID>}・{@code TWITCH_stream_<配信ID>}・
 *                                {@code TWITCH_video_<VOD の ID>} の形
 * @param channelId               動画のチャンネルの主キー
 * @param channelName             チャンネル名
 * @param platform                配信元（{@code YOUTUBE} / {@code TWITCH}）
 * @param platformLabel           画面に出す配信元の名前（{@code Platform.displayName()}）。{@code platform} は絞り込みなどの値として残す
 * @param title                   動画のタイトル
 * @param watchUrl                視聴ページの URL。{@code state} が {@code LIVE} で配信中の URL が分かっていればそちらを返す
 * @param thumbnailUrl            このアプリのサムネイルの経路（{@code /api/videos/{id}/thumbnail}）。外部の画像 URL は返さない
 * @param publishedAt             公開された時刻（配信は巡回で最初に見つけた時刻のことがある）
 * @param lastObservedAt          巡回で最後に配信中と確認した時刻。一度も確認していなければ {@code null}
 * @param state                   {@code LIVE}：配信中の印があり、アプリ起動後の巡回で観測でき、チャンネルの判定も失敗していない。
 *                                {@code VIDEO}：配信中ではない（アーカイブ・投稿動画・配信予定）。
 *                                {@code UNKNOWN}：配信中の印はあるが、印が古いかもしれず今も配信中かを確かめられていない
 * @param playable                画面で再生できるか。YouTube の動画、Twitch の VOD、{@code state} が {@code LIVE} の配信なら {@code true}
 * @param thumbnailRetryExhausted サムネイルの取得が再試行の上限に達し、もう取りに行かないか
 * @param contentKind             種類（{@code UPCOMING} / {@code STREAM} / {@code UPLOAD} / {@code MISSING}）。まだ判定できていなければ {@code null}。{@code MISSING}（削除された・存在しない待機所）は段ごとの一覧には出ず、段を指定しない一覧と 1 件の取得でだけ返る
 * @param scheduledStartTime      配信予定の開始時刻。配信予定以外では {@code null}
 */
public record OnlineVideoResponse(String id, Long channelId, String channelName, String platform,
        String platformLabel, String title, String watchUrl, String thumbnailUrl, Instant publishedAt,
        Instant lastObservedAt, String state, boolean playable, boolean thumbnailRetryExhausted,
        String contentKind, Instant scheduledStartTime) {}
