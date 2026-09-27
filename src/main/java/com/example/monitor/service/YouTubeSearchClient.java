package com.example.monitor.service;

import com.example.monitor.dto.YouTubeSearchRequest;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.SearchListResponse;
import com.google.api.services.youtube.model.Video;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 利用者の検索と視聴画面のために YouTube Data API を呼ぶ。
 *
 * <p>回数の管理・使い回し・エラーの読み替えは {@link YouTubeSearchService} の役割で、
 * ここは API の呼び出しだけを持つ。{@link YouTubeApiClient} と分けたのは、あちらが
 * 失敗を空の結果に丸める（監視・登録の用途ではそれでよい）のに対し、検索では
 * 「上限に達した」と「失敗した」を利用者に区別して返す必要があり、例外をそのまま上げたいため。
 * API キーは {@link YouTubeApiClient} と同じ {@link YouTube} の Bean が付ける。
 *
 * <table border="1">
 *   <caption>メソッドごとの消費</caption>
 *   <tr><th>メソッド</th><th>使用 API</th><th>消費</th></tr>
 *   <tr><td>{@link #searchVideoIds}</td><td>search.list</td><td>検索 1 回（旧方式なら 100 単位）</td></tr>
 *   <tr><td>{@link #fetchVideos}</td><td>videos.list</td><td>1 単位（50 件まで）</td></tr>
 *   <tr><td>{@link #fetchChannels}</td><td>channels.list</td><td>1 単位（50 件まで）</td></tr>
 * </table>
 */
@Component
@RequiredArgsConstructor
public class YouTubeSearchClient {

    /**
     * 1 回の検索で取る件数。消費は 20 件でも 50 件でも同じなので上限まで取る
     * （このサービスの条件で絞ったあとに残る件数を増やすため）。
     */
    private static final long MAX_RESULTS = 50L;

    private final YouTube youtube;

    /**
     * 公式の条件で動画を検索する。
     *
     * <p>{@code type=video}・{@code videoEmbeddable=true}（視聴画面で埋め込んで再生するため）・
     * {@code regionCode=JP}・{@code relevanceLanguage=ja} は常に付ける。
     *
     * @param request   条件（公式の条件だけを使う）
     * @param pageToken 読むページ。最初のページなら {@code null}
     * @return 見つかった動画 ID（並び順のまま）と次のページの印
     * @throws IOException API が失敗した場合（403 {@code quotaExceeded} を含む）
     */
    public SearchPage searchVideoIds(YouTubeSearchRequest request, String pageToken) throws IOException {
        SearchListResponse response = youtube.search().list(List.of("id"))
                .setType(List.of("video"))
                .setVideoEmbeddable("true")
                .setRegionCode("JP")
                .setRelevanceLanguage("ja")
                .setMaxResults(MAX_RESULTS)
                .setQ(request.q())
                .setOrder(request.order())
                .setPublishedAfter(request.publishedAfter())
                .setPublishedBefore(request.publishedBefore())
                .setVideoDuration(request.duration())
                .setEventType(request.eventType())
                .setChannelId(request.channelId())
                .setVideoCategoryId(request.categoryId())
                .setVideoDefinition(request.definition())
                .setVideoCaption(request.caption() == null ? null : "closedCaption")
                .setVideoLicense(request.license())
                .setSafeSearch(request.safeSearch() == null ? "moderate" : request.safeSearch())
                .setPageToken(pageToken)
                .execute();
        List<String> videoIds = response.getItems() == null ? List.of() : response.getItems().stream()
                .map(item -> item.getId().getVideoId())
                .filter(Objects::nonNull)
                .toList();
        return new SearchPage(videoIds, response.getNextPageToken());
    }

    /**
     * 動画の詳細をまとめて取る（50 件まで）。
     *
     * @param videoIds 動画 ID
     * @return 見つかった動画（削除・非公開になったものは含まれない。順序は保証されない）
     * @throws IOException API が失敗した場合
     */
    public List<Video> fetchVideos(Collection<String> videoIds) throws IOException {
        if (videoIds.isEmpty()) {
            return List.of();
        }
        List<Video> items = youtube.videos()
                .list(List.of("snippet", "contentDetails", "statistics", "status", "liveStreamingDetails"))
                .setId(List.copyOf(videoIds))
                .execute()
                .getItems();
        return items == null ? List.of() : items;
    }

    /**
     * チャンネルの情報（名前・アイコン・登録者数・動画数）をまとめて取る（50 件まで）。
     *
     * @param channelIds チャンネル ID
     * @return 見つかったチャンネル
     * @throws IOException API が失敗した場合
     */
    public List<Channel> fetchChannels(Collection<String> channelIds) throws IOException {
        if (channelIds.isEmpty()) {
            return List.of();
        }
        List<Channel> items = youtube.channels()
                .list(List.of("snippet", "statistics"))
                .setId(List.copyOf(channelIds))
                .execute()
                .getItems();
        return items == null ? List.of() : items;
    }

    /**
     * 検索 1 ページぶんの結果。
     *
     * @param videoIds      動画 ID（並び順のまま）
     * @param nextPageToken 次のページの印。無ければ {@code null}
     */
    public record SearchPage(List<String> videoIds, String nextPageToken) {}
}
