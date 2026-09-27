package com.example.monitor.dto;

import com.example.monitor.service.SearchQuotaStatus;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/my/search} の応答。
 *
 * @param items             結果（YouTube の並び順のまま）
 * @param nextPageToken     続きを読むときに渡す印。続きが無ければ {@code null}
 * @param filteredByService このサービスの条件で絞り込んだか。{@code true} なら画面に
 *                          「このサービスの条件で絞り込み済み」と出す（YouTube API の規約 III.C.5）
 * @param fetchedAt         YouTube から取った時刻（使い回したときは、取った元の時刻）
 * @param quota             検索の今日の残り
 */
public record YouTubeSearchResponse(
        List<YouTubeVideoResponse> items,
        String nextPageToken,
        boolean filteredByService,
        Instant fetchedAt,
        SearchQuotaStatus quota
) {}
