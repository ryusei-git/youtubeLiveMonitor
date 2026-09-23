package com.example.monitor.service;

import com.example.monitor.dto.OnlineVideoCandidate;
import com.example.monitor.util.YouTubeFeedParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.io.IOException;

/** ローカル環境でも公開コールバック不要で投稿を拾い、検索APIのクォータを消費しない。 */
@Service @RequiredArgsConstructor
public class YouTubeVideoFeedClient {
    private final HttpClient httpClient;

    public List<OnlineVideoCandidate> fetch(String channelId) throws IOException, InterruptedException {
        if (!channelId.matches("UC[A-Za-z0-9_-]{22}")) throw new IllegalArgumentException("チャンネルIDが不正です");
        var request = HttpRequest.newBuilder(URI.create("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId))
                .timeout(Duration.ofSeconds(12)).GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("動画フィード取得失敗 HTTP " + response.statusCode());
        if (response.body().length() > 1_000_000) throw new IOException("動画フィードが大きすぎます");
        return YouTubeFeedParser.parse(response.body(), channelId);
    }
}
