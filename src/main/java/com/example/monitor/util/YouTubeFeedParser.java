package com.example.monitor.util;

import com.example.monitor.dto.OnlineVideoCandidate;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 外部実体を解決しないXMLパーサーを使い、フィードから外部ファイルを読ませない。 */
public final class YouTubeFeedParser {
    private YouTubeFeedParser() {}

    public static List<OnlineVideoCandidate> parse(String xml, String channelId) {
        var document = Jsoup.parse(xml, "", Parser.xmlParser());
        if (document.getElementsByTag("feed").isEmpty()) {
            throw new IllegalArgumentException("YouTubeの動画フィードではありません");
        }
        List<OnlineVideoCandidate> result = new ArrayList<>();
        for (var entry : document.getElementsByTag("entry")) {
            String id = entry.getElementsByTag("yt:videoId").text();
            if (!channelId.equals(entry.getElementsByTag("yt:channelId").text())
                    || !YouTubeWatchUrl.isVideoId(id)) continue;
            Instant published = Instant.parse(entry.getElementsByTag("published").text());
            result.add(new OnlineVideoCandidate("YOUTUBE_" + id,
                    entry.getElementsByTag("title").text(), YouTubeWatchUrl.of(id),
                    YouTubeWatchUrl.thumbnailOf(id), published));
        }
        return result;
    }
}
