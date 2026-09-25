package com.example.monitor.service;

import com.example.monitor.dto.OnlineVideoCandidate;
import com.example.monitor.util.YouTubeWatchUrl;
import com.google.api.services.youtube.YouTube;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YouTube Data API の uploads 再生リストから、チャンネルの投稿を取る。
 * <p>フィードが404等になっても投稿を取得できるよう、公式のuploads一覧を予備経路にする。
 */
@Service @RequiredArgsConstructor
public class YouTubeUploadsClient {
    private final YouTube youtube;
    private final YouTubeCatalogQuota quota;
    private final Map<String, String> uploads = new ConcurrentHashMap<>();

    /**
     * チャンネルの投稿を uploads 再生リストの先頭から、{@code since} より古い投稿に達したページまで取る。
     * <p>1 ページ（最大 50 件）ごとにクォータを 1 単位使う。そのチャンネルの uploads 再生リストの ID をまだ覚えていなければ、最初に {@code channels.list} でもう 1 単位使う。境界のページはまるごと返すので、{@code since} より古い投稿も一部含まれる。
     *
     * @param channelId YouTube のチャンネル ID（{@code UC} で始まる）
     * @param since この時刻より古い投稿に達したら、次のページを取らない
     * @return 取れた投稿（{@code since} より古いものも含む）
     * @throws IOException 本日のクォータの上限に達した、uploads 再生リストが見つからない・応答が不正、API の呼び出しに失敗した、またはスレッドが割り込まれた場合
     */
    public List<OnlineVideoCandidate> fetch(String channelId, Instant since) throws IOException {
        String playlist = uploadsPlaylistId(channelId);
        List<OnlineVideoCandidate> videos = new ArrayList<>();
        String token = null;
        boolean reachedBoundary;
        do {
            if (Thread.currentThread().isInterrupted()) throw new IOException("動画取得を中断しました");
            quota.acquire();
            var response = youtube.playlistItems().list(List.of("snippet", "contentDetails"))
                    .setPlaylistId(playlist).setMaxResults(50L).setPageToken(token).execute();
            reachedBoundary = false;
            if (response.getItems() == null) throw new IOException("投稿一覧の応答が不正です");
            for (var item : response.getItems()) {
                var details = item.getContentDetails();
                var snippet = item.getSnippet();
                if (details == null || snippet == null || details.getVideoPublishedAt() == null) continue;
                String id = details.getVideoId();
                if (!YouTubeWatchUrl.isVideoId(id)) continue;
                Instant published = Instant.ofEpochMilli(details.getVideoPublishedAt().getValue());
                if (published.isBefore(since)) reachedBoundary = true;
                videos.add(new OnlineVideoCandidate("YOUTUBE_" + id, snippet.getTitle(),
                        YouTubeWatchUrl.of(id),
                        YouTubeWatchUrl.thumbnailOf(id), published));
            }
            token = response.getNextPageToken();
        } while (!reachedBoundary && token != null && !token.isBlank());
        return videos;
    }

    /** uploads 再生リストの ID はチャンネルごとに変わらないので、最初の 1 回だけ {@code channels.list}（1 単位）で引いて覚えておく。 */
    private String uploadsPlaylistId(String channelId) throws IOException {
        String playlist = uploads.get(channelId);
        if (playlist == null) {
            quota.acquire();
            var response = youtube.channels().list(List.of("contentDetails")).setId(List.of(channelId)).execute();
            if (response.getItems() == null) throw new IOException("投稿一覧が取得できません");
            for (var channel : response.getItems()) {
                if (channelId.equals(channel.getId()) && channel.getContentDetails() != null
                        && channel.getContentDetails().getRelatedPlaylists() != null) {
                    playlist = channel.getContentDetails().getRelatedPlaylists().getUploads();
                }
            }
            if (playlist == null || playlist.isBlank()) throw new IOException("投稿一覧が見つかりません");
            uploads.put(channelId, playlist);
        }
        return playlist;
    }
}
