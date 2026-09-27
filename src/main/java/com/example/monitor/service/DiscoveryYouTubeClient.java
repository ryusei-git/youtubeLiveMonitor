package com.example.monitor.service;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.PlaylistItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 新人発掘が使う YouTube Data API の呼び出し（Issue #488）。
 *
 * <p>検索の API（#487）と同時に作ったため、呼び出しをこのクラスに閉じている（共通化は後の Issue）。
 * 検索の回数（{@link YouTubeSearchBudget}）を数えるのは呼ぶ側の役目で、ここは API を叩くだけにする。
 */
@Component
@RequiredArgsConstructor
public class DiscoveryYouTubeClient {

    /** {@code channels.list} の 1 回で引ける ID の数（API の上限）。 */
    public static final int CHANNELS_PER_REQUEST = 50;

    private final YouTube youtube;

    /**
     * 検索で見つけた動画 1 件。
     *
     * @param videoId    動画 ID
     * @param channelId  チャンネル ID
     * @param videoTitle 動画のタイトル（API の値のまま）
     */
    public record SearchHit(String videoId, String channelId, String videoTitle) {}

    /**
     * 新しい順に動画を検索する（{@code search.list}。検索 1 回）。次のページは取らない
     * （検索は網羅ではなく「種まき」と割り切る。親 #485）。
     *
     * @param term           検索語
     * @param publishedAfter この時刻より後に公開された動画だけ
     * @return 見つけた動画
     * @throws IOException API の呼び出しに失敗した場合
     */
    public List<SearchHit> searchRecentVideos(String term, Instant publishedAfter) throws IOException {
        var response = youtube.search().list(List.of("snippet"))
                .setQ(term).setType(List.of("video")).setOrder("date")
                .setPublishedAfter(publishedAfter.toString())
                .setRegionCode("JP").setRelevanceLanguage("ja").setMaxResults(50L)
                .execute();
        List<SearchHit> hits = new ArrayList<>();
        if (response.getItems() == null) return hits;
        for (var item : response.getItems()) {
            if (item.getId() == null || item.getSnippet() == null || item.getSnippet().getChannelId() == null) continue;
            hits.add(new SearchHit(item.getId().getVideoId(), item.getSnippet().getChannelId(), item.getSnippet().getTitle()));
        }
        return hits;
    }

    /**
     * チャンネルの値を引く（{@code channels.list}、{@value #CHANNELS_PER_REQUEST} 件で 1 単位）。
     * 消えたチャンネルは結果に含まれない。
     *
     * @param channelIds チャンネル ID（何件でもよい。{@value #CHANNELS_PER_REQUEST} 件ずつに分けて引く）
     * @return 見つかったチャンネル
     * @throws IOException API の呼び出しに失敗した場合
     */
    public List<Channel> channels(List<String> channelIds) throws IOException {
        List<Channel> channels = new ArrayList<>();
        for (int i = 0; i < channelIds.size(); i += CHANNELS_PER_REQUEST) {
            var response = youtube.channels().list(List.of("snippet", "statistics", "contentDetails"))
                    .setId(channelIds.subList(i, Math.min(i + CHANNELS_PER_REQUEST, channelIds.size())))
                    .setMaxResults((long) CHANNELS_PER_REQUEST)
                    .execute();
            if (response.getItems() != null) channels.addAll(response.getItems());
        }
        return channels;
    }

    /**
     * アップロードの再生リストを 1 ページ（50 件）だけ引き、最も古い公開日時を返す（1 単位）。
     * 新人は動画が少ないので、1 ページでほぼ全件になる。
     *
     * @param uploadsPlaylistId アップロードの再生リストの ID（{@code UU...}）
     * @return 最も古い公開日時。動画が無ければ空
     * @throws IOException API の呼び出しに失敗した場合
     */
    public Optional<Instant> oldestUpload(String uploadsPlaylistId) throws IOException {
        var response = youtube.playlistItems().list(List.of("contentDetails"))
                .setPlaylistId(uploadsPlaylistId).setMaxResults(50L).execute();
        if (response.getItems() == null) return Optional.empty();
        return response.getItems().stream()
                .map(PlaylistItem::getContentDetails)
                .filter(Objects::nonNull)
                .map(details -> details.getVideoPublishedAt())
                .filter(Objects::nonNull)
                .map(DiscoveryYouTubeClient::toInstant)
                .min(Instant::compareTo);
    }

    /**
     * API が「今日のクォータを使い切った」（403 の {@code quotaExceeded}）と返したかを見分ける。
     *
     * @param e 呼び出しの失敗
     * @return {@code quotaExceeded} なら {@code true}
     */
    public static boolean isQuotaExceeded(IOException e) {
        return e instanceof GoogleJsonResponseException json && json.getStatusCode() == 403
                && json.getDetails() != null && json.getDetails().getErrors() != null
                && json.getDetails().getErrors().stream().anyMatch(error -> "quotaExceeded".equals(error.getReason()));
    }

    /**
     * ログに出す失敗の説明。例外の本文は出さない（API のエラーの URL にはキーが入りうるため。
     * {@code OnlineVideoCollector} と同じ）。
     *
     * @param e 呼び出しの失敗
     * @return 状態コードと理由だけの説明
     */
    public static String describe(IOException e) {
        if (e instanceof GoogleJsonResponseException json) {
            String reason = json.getDetails() != null && json.getDetails().getErrors() != null
                    && !json.getDetails().getErrors().isEmpty() ? json.getDetails().getErrors().get(0).getReason() : "";
            return "HTTP " + json.getStatusCode() + " " + reason;
        }
        return e.getClass().getSimpleName();
    }

    /**
     * API の日時を {@link Instant} にする。
     *
     * @param dateTime API の日時。{@code null} 可
     * @return 変換した日時。{@code null} なら {@code null}
     */
    public static Instant toInstant(DateTime dateTime) {
        return dateTime == null ? null : Instant.ofEpochMilli(dateTime.getValue());
    }
}
