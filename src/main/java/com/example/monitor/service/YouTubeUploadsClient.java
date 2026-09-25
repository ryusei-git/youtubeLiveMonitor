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

/** フィードが404等になっても投稿を取得できるよう、公式のuploads一覧を予備経路にする。 */
@Service @RequiredArgsConstructor
public class YouTubeUploadsClient {
    private final YouTube youtube;
    private final YouTubeCatalogQuota quota;
    private final Map<String, String> uploads = new ConcurrentHashMap<>();

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
