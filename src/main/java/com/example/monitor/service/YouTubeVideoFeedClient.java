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

/**
 * YouTube の公開フィード（RSS）からチャンネルの新しい投稿を取る。
 *
 * <p>ローカル環境でも公開コールバック不要で投稿を拾い、検索APIのクォータを消費しない。
 */
@Service @RequiredArgsConstructor
public class YouTubeVideoFeedClient {
    private final HttpClient httpClient;

    /**
     * チャンネルのフィードを取り、載っている動画を返す。
     *
     * <p>不正なチャンネル ID とフィードでない応答を {@link IllegalArgumentException} に、通信の失敗を
     * {@link IOException} に分けているのは、呼び出し側（{@code OnlineVideoCollector}）がこの 2 種類と
     * 日付を読めない場合の {@link java.time.DateTimeException} だけを捕まえて公式 API の予備経路へ切り替えるため。例外の種類を変えると予備経路が黙って動かなくなる。
     *
     * @param channelId チャンネル ID（{@code UC} で始まる 24 文字）
     * @return フィードに載っている、そのチャンネル自身の動画。無ければ空リスト
     * @throws IllegalArgumentException チャンネル ID の形が不正、またはフィードでない応答だった場合
     *                                  （呼び出し側はこれを受けて公式 API の予備経路へ切り替える）
     * @throws IOException 通信に失敗した、または 200 以外・100 万文字を超える応答だった場合
     * @throws java.time.format.DateTimeParseException フィードの投稿日時（{@code published}）を読めなかった場合
     *                                  （日付を読めない場合も、呼び出し側は公式 API の予備経路へ切り替える）
     * @throws InterruptedException 待っている間に割り込まれた場合
     */
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
