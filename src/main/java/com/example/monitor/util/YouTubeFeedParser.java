package com.example.monitor.util;

import com.example.monitor.dto.OnlineVideoCandidate;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTube のチャンネルの動画フィード（Atom の XML）から、動画の候補を読み取る。
 *
 * <p>外部実体を解決しないXMLパーサーを使い、フィードから外部ファイルを読ませない。
 */
public final class YouTubeFeedParser {
    private YouTubeFeedParser() {}

    /**
     * フィードの XML から、指定したチャンネルの動画の候補を返す。
     *
     * @param xml フィードの XML
     * @param channelId 候補に残すチャンネルのID。これと異なるチャンネルの項目は飛ばす
     * @return 動画の候補。動画IDとして正しくない項目は含めない。該当が無ければ空のリスト
     * @throws IllegalArgumentException {@code feed} 要素が無く、フィードでない XML の場合
     * @throws java.time.format.DateTimeParseException 残す項目の公開日時が無いか読めない場合
     */
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
