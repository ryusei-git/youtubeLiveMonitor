package com.example.monitor.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * {@code GET /api/my/search} のクエリパラメータ。
 *
 * <h2>公式の条件とこのサービスの条件を分けている理由</h2>
 * 前半（{@code q}〜{@code pageToken}）は {@code search.list} にそのまま渡す公式の条件で、
 * 変えると検索をやり直す（回数を使う）。後半は取ってきた結果にあとから当てるこのサービスの条件で、
 * 変えても検索の回数を使わない。使い回し（6 時間。「ライブ中」は 15 分）の鍵は前半だけから作る。
 *
 * <p>値の選択肢は {@code search.list} の仕様そのまま。範囲外は YouTube に送る前に 400 にする
 * （送ってから 400 を受けると、検索の回数だけ減ってしまうため）。
 *
 * @param q                検索語。{@code channelId} があるときは空でもよい
 * @param order            並び順
 * @param publishedAfter   この日時以降に投稿（ISO-8601）
 * @param publishedBefore  この日時より前に投稿（ISO-8601）
 * @param duration         長さの区分（{@code videoDuration}）
 * @param eventType        配信の区分
 * @param channelId        チャンネルで絞る
 * @param categoryId       動画のカテゴリで絞る（{@code videoCategoryId}）
 * @param definition       画質（{@code videoDefinition}）
 * @param caption          {@code true} で字幕ありに絞る（{@code videoCaption=closedCaption}）
 * @param license          {@code creativeCommon} でクリエイティブ・コモンズに絞る
 * @param safeSearch       セーフサーチ（既定 {@code moderate}）
 * @param pageToken        次のページの印（前の応答の {@code nextPageToken}）
 * @param minDurationSec   長さの下限（秒）
 * @param maxDurationSec   長さの上限（秒）
 * @param minViews         再生回数の下限
 * @param maxViews         再生回数の上限
 * @param minLikes         高評価の下限（非公開の動画は除く）
 * @param maxSubscribers   チャンネル登録者の上限（非公開のチャンネルは残す）
 * @param maxChannelVideos チャンネルの動画数の上限
 * @param excludeShorts    3 分以下を除く
 * @param titleIncludes    タイトルに含む語（カンマ区切りのどれか。大文字小文字を区別しない）
 * @param titleExcludes    タイトルに含まない語（カンマ区切り）
 * @param withinHours      投稿から N 時間以内
 * @param onlyRegistered   監視中のチャンネルだけ
 * @param excludeRegistered 監視中のチャンネルを除く
 * @param excludeSaved     サービスに保存済みの動画を除く
 */
public record YouTubeSearchRequest(
        String q,
        @Pattern(regexp = "relevance|date|viewCount|rating|title",
                message = "relevance・date・viewCount・rating・title のどれかを指定してください")
        String order,
        String publishedAfter,
        String publishedBefore,
        @Pattern(regexp = "any|short|medium|long", message = "any・short・medium・long のどれかを指定してください")
        String duration,
        @Pattern(regexp = "live|upcoming|completed", message = "live・upcoming・completed のどれかを指定してください")
        String eventType,
        String channelId,
        String categoryId,
        @Pattern(regexp = "hd|standard", message = "hd・standard のどちらかを指定してください")
        String definition,
        @Pattern(regexp = "true", message = "true だけを指定できます")
        String caption,
        @Pattern(regexp = "creativeCommon", message = "creativeCommon だけを指定できます")
        String license,
        @Pattern(regexp = "none|moderate|strict", message = "none・moderate・strict のどれかを指定してください")
        String safeSearch,
        String pageToken,
        @PositiveOrZero Integer minDurationSec,
        @PositiveOrZero Integer maxDurationSec,
        @PositiveOrZero Long minViews,
        @PositiveOrZero Long maxViews,
        @PositiveOrZero Long minLikes,
        @PositiveOrZero Long maxSubscribers,
        @PositiveOrZero Long maxChannelVideos,
        Boolean excludeShorts,
        String titleIncludes,
        String titleExcludes,
        @PositiveOrZero Integer withinHours,
        Boolean onlyRegistered,
        Boolean excludeRegistered,
        Boolean excludeSaved
) {

    /**
     * このサービスの条件が 1 つでも指定されているかを返す。
     *
     * <p>指定があれば、結果は YouTube の検索結果そのものではなくなる。画面に
     * 「このサービスの条件で絞り込み済み」と出す（YouTube API の規約 III.C.5）ために使う。
     *
     * @return 指定があれば {@code true}
     */
    public boolean hasServiceFilters() {
        return minDurationSec != null || maxDurationSec != null || minViews != null || maxViews != null
                || minLikes != null || maxSubscribers != null || maxChannelVideos != null
                || Boolean.TRUE.equals(excludeShorts) || hasText(titleIncludes) || hasText(titleExcludes)
                || withinHours != null || Boolean.TRUE.equals(onlyRegistered)
                || Boolean.TRUE.equals(excludeRegistered) || Boolean.TRUE.equals(excludeSaved);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
