package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.ChannelSearchResult;
import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.exception.SearchQuotaExceededException;
import com.example.monitor.exception.YouTubeApiUnavailableException;
import com.example.monitor.util.ApiKeyRedactor;
import com.example.monitor.util.EpochTimeConverter;
import com.example.monitor.util.HtmlEntities;
import com.example.monitor.util.YouTubeWatchUrl;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.ChannelListResponse;
import com.google.api.services.youtube.model.ChannelSnippet;
import com.google.api.services.youtube.model.SearchListResponse;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoListResponse;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoLiveStreamingDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * YouTube Data API v3 を呼び出す窓口。
 *
 * <p>配信の検知そのものは行わない（それは {@link LiveStreamDetector} の役割）。
 * このクラスが担うのは「クォータを消費してでも公式 API から取る必要がある情報」だけで、
 * 各メソッドのクォータ消費量が大きく異なる点に注意して使い分ける。
 *
 * <table border="1">
 *   <caption>メソッドごとのクォータ消費量</caption>
 *   <tr><th>メソッド</th><th>使用 API</th><th>消費クォータ</th><th>想定される呼び出し頻度</th></tr>
 *   <tr><td>{@link #fetchLiveStreamDetails}</td><td>videos.list</td><td>1</td><td>配信を検知した瞬間だけ</td></tr>
 *   <tr><td>{@link #searchChannelsByName}</td><td>search.list</td><td>100</td><td>管理者が手動で検索したときだけ（{@link YouTubeSearchBudget} で回数を数える）</td></tr>
 *   <tr><td>{@link #resolveHandleToChannelId}</td><td>channels.list</td><td>1</td><td>ハンドル形式のチャンネル登録時だけ</td></tr>
 *   <tr><td>{@link #fetchChannelTitle}</td><td>channels.list</td><td>1</td><td>表示名を空にした購読で、新しくチャンネルを登録するときだけ</td></tr>
 * </table>
 *
 * <p>失敗のログには例外の本体を渡さず {@link ApiKeyRedactor#describe} の説明だけを書く。
 * Google の例外の本文とスタックにはキー付きの URL が入るため（Issue #495）。
 *
 * @see LiveStreamDetector 配信中かどうかの検知（クォータ消費なし）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class YouTubeApiClient {

    /** 名前検索で返す候補の最大件数。 */
    private static final long SEARCH_RESULT_LIMIT = 10L;

    private final YouTube youtube;

    /** 管理者のチャンネル名検索（{@code search.list}）の回数を、利用者の検索・発掘と同じ予算で数える。 */
    private final YouTubeSearchBudget searchBudget;

    /** API キーの有無を、API を呼ぶ前に確かめるために使う。 */
    private final MonitorProperties monitorProperties;

    /**
     * 動画 ID から配信の詳細情報を取得する。消費クォータは 1。
     *
     * <p>{@link LiveStreamDetector} が配信を検知した直後に、通知文を組み立てる材料
     * （タイトル・サムネイル等）を揃える目的で呼ぶ。
     *
     * @param videoId 対象の動画 ID
     * @return 取得できた詳細情報。動画が見つからない／通信に失敗した場合は {@link Optional#empty()}
     */
    public Optional<LiveStreamDetails> fetchLiveStreamDetails(String videoId) {
        try {
            VideoListResponse response = youtube.videos()
                    .list(List.of("snippet", "liveStreamingDetails"))
                    .setId(List.of(videoId))
                    .execute();

            if (response.getItems() == null || response.getItems().isEmpty()) {
                log.warn("動画の詳細情報が見つかりませんでした: video={}", videoId);
                return Optional.empty();
            }

            return Optional.of(toLiveStreamDetails(videoId, response.getItems().get(0)));

        } catch (IOException e) {
            log.error("動画の詳細情報の取得に失敗しました: video={}, reason={}", videoId, ApiKeyRedactor.describe(e));
            return Optional.empty();
        }
    }

    /**
     * チャンネル名の部分一致でチャンネルを検索する。消費クォータは 100。
     *
     * <p>1 日の上限（既定 10,000）に対して 1 回 100 は重いため、
     * 定期監視では絶対に使わず、チャンネル ID が分からないときの手動検索に限って使う。
     *
     * <p>API を叩く前に {@link YouTubeSearchBudget#acquireForAdmin()} で検索の回数を数える。呼び出し元は
     * 管理画面（{@code GET /api/channels/search}）と CLI（{@code channel search}）の 2 つあり、片方でも数え忘れると
     * 利用者の検索・新人発掘と合わせた 1 日の上限の前提が崩れる。そのため呼ぶ側ではなくここで数える。
     * 利用者の検索（{@link YouTubeSearchService}）は呼ぶ側で数えているが、それは 6 時間の使い回しに
     * 当たったときに数えないためで、こちらには使い回しが無い。
     *
     * <p>YouTube が {@code quotaExceeded} を返したときは空のリストにせず、{@link YouTubeSearchBudget#markExhausted()}
     * でその日の検索を止めてから例外にする。空で返すと、上限で検索できなかったのに「該当するチャンネルが
     * 見つかりませんでした」と表示されるため。
     *
     * <p>API キーが無いとき・呼び出しに失敗したときも空のリストにせず、例外にする。空で返すと
     * 「該当するチャンネルが見つかりませんでした」と表示され、管理者にはキーが無いのか本当に 0 件なのかが
     * 分からない（実際に発生した）。キーは回数を数える前に確かめる（API を呼ばないのに回数を使わないため）。
     *
     * @param query 検索したいチャンネル名（部分一致）
     * @return 見つかった候補。該当なしの場合は空リスト
     * @throws SearchQuotaExceededException 検索の回数が本日の上限に達している場合、または YouTube が
     *                                      {@code quotaExceeded} を返した場合（管理画面では 429 になる）
     * @throws YouTubeApiUnavailableException API キーが設定されていない場合（検索の回数は使わない）、
     *                                        または API の呼び出しに失敗した場合（管理画面では 503 になる）
     */
    public List<ChannelSearchResult> searchChannelsByName(String query) {
        requireApiKey();
        searchBudget.acquireForAdmin();
        List<ChannelSearchResult> searchResults = new ArrayList<>();
        try {
            SearchListResponse response = youtube.search()
                    .list(List.of("snippet"))
                    .setQ(query)
                    .setType(List.of("channel"))
                    .setMaxResults(SEARCH_RESULT_LIMIT)
                    .execute();

            if (response.getItems() == null) {
                return searchResults;
            }

            for (SearchResult item : response.getItems()) {
                // search.list の名前は文字参照のまま返る。管理画面の「登録」はこの名前をチャンネル名として保存するので、ここで戻す
                searchResults.add(new ChannelSearchResult(
                        item.getId().getChannelId(),
                        HtmlEntities.unescape(item.getSnippet().getTitle()),
                        item.getSnippet().getThumbnails().getDefault().getUrl()
                ));
            }
        } catch (IOException e) {
            if (DiscoveryYouTubeClient.isQuotaExceeded(e)) {
                searchBudget.markExhausted();
                log.warn("YouTube のクォータを使い切ったので、今日の検索を止めます: query={}", query);
                throw new SearchQuotaExceededException();
            }
            log.error("チャンネル名検索に失敗しました: query={}, reason={}", query, ApiKeyRedactor.describe(e));
            throw new YouTubeApiUnavailableException("YouTube API の呼び出しに失敗しました");
        }
        return searchResults;
    }

    /**
     * YouTube のハンドル（{@code @}から始まる名前）を、実際のチャンネル ID に変換する。消費クォータは 1。
     *
     * <p>YouTube の URL には {@code UC} から始まる本来のチャンネル ID と、
     * 利用者が自由に設定できるハンドルの 2 種類がある。{@link LiveStreamDetector} が使う
     * {@code /channel/{id}/live} という URL 形式はハンドルでは機能しないため
     * （実際にハンドルを登録した利用者が 404 に遭遇した）、登録時にこのメソッドで
     * 必ず本来のチャンネル ID へ解決してから保存する。
     *
     * @param handle {@code @} を含むハンドル文字列（例: {@code @example}）
     * @return 解決できた場合はチャンネル ID。該当するチャンネルが無い場合は {@link Optional#empty()}
     * @throws YouTubeApiUnavailableException API キーが設定されていない場合（API は呼ばない）、
     *                                        または API の呼び出しに失敗した場合。空にすると
     *                                        「ハンドルに該当するチャンネルが見つかりません」と表示され、
     *                                        キーが無いことに気づけない（実際に発生した）
     */
    public Optional<String> resolveHandleToChannelId(String handle) {
        requireApiKey();
        try {
            ChannelListResponse response = youtube.channels()
                    .list(List.of("id"))
                    .setForHandle(handle)
                    .execute();

            if (response.getItems() == null || response.getItems().isEmpty()) {
                log.warn("ハンドルに該当するチャンネルが見つかりませんでした: handle={}", handle);
                return Optional.empty();
            }

            return Optional.of(response.getItems().get(0).getId());

        } catch (IOException e) {
            log.error("ハンドルの解決に失敗しました: handle={}, reason={}", handle, ApiKeyRedactor.describe(e));
            if (DiscoveryYouTubeClient.isQuotaExceeded(e)) {
                throw new YouTubeApiUnavailableException("YouTube API の本日の上限に達しました");
            }
            throw new YouTubeApiUnavailableException("YouTube API の呼び出しに失敗しました");
        }
    }

    /**
     * チャンネル ID から、YouTube 上のチャンネル名を取る。消費クォータは 1。
     *
     * <p>利用者が表示名を空にして購読したとき、チャンネル名が {@code UC...} のままにならないよう、
     * 新しく登録する時点で 1 回だけ呼ぶ（{@code YouTubeStreamPlatform.fetchChannelTitle}）。巡回からは呼ばない。
     * ハンドルで入力された場合も、解決後の {@code UC...} で引く（{@link #resolveHandleToChannelId} の戻り値の形を
     * 変えずに済むため。登録は手動の操作なので、1 回ぶん余分に使っても問題にならない）。
     *
     * <p>取れなかったら {@link Optional#empty()} を返し、登録は続けさせる。名前は表示のためだけのものなので、
     * ここで失敗させると、API キーが無いだけで {@code UC...} 形式の購読までできなくなる。
     *
     * <p>{@code IOException} だけでなく実行時例外も捕まえる。Google のクライアントは、2xx の応答の
     * 本文を解析できないとき、{@code IOException} ではなく {@code IllegalArgumentException}（本文が
     * 空・型の不一致・日時の書式違い）や {@code NullPointerException}（途中で切れた JSON）を投げる
     * ことがあるため。捕まえないと {@code StreamPlatform.fetchChannelTitle} の「例外を投げない」約束が
     * 破れ、名前が取れないだけで購読が 400・500 で失敗する。
     *
     * @param channelId {@code UC...} 形式のチャンネル ID
     * @return チャンネル名。見つからない／空／通信に失敗した／応答を解析できなかった場合は
     *         {@link Optional#empty()}
     */
    public Optional<String> fetchChannelTitle(String channelId) {
        try {
            ChannelListResponse response = youtube.channels()
                    .list(List.of("snippet"))
                    .setId(List.of(channelId))
                    .execute();

            if (response.getItems() == null || response.getItems().isEmpty()) {
                log.warn("チャンネル名を取得できませんでした（該当なし）: channel={}", channelId);
                return Optional.empty();
            }

            ChannelSnippet snippet = response.getItems().get(0).getSnippet();
            String title = snippet == null ? null : snippet.getTitle();
            return title == null || title.isBlank() ? Optional.empty() : Optional.of(title.strip());

        } catch (IOException | RuntimeException e) {
            log.warn("チャンネル名の取得に失敗しました: channel={}, reason={}", channelId, ApiKeyRedactor.describe(e));
            return Optional.empty();
        }
    }

    /**
     * API のレスポンスをアプリ内部で扱う形に変換する。
     *
     * @param videoId 動画 ID
     * @param video   API から返された動画情報
     * @return 変換後の配信詳細情報
     */
    private LiveStreamDetails toLiveStreamDetails(String videoId, Video video) {
        VideoSnippet snippet = video.getSnippet();
        VideoLiveStreamingDetails streamingDetails = video.getLiveStreamingDetails();

        LiveStreamDetails.LiveStreamDetailsBuilder builder = LiveStreamDetails.builder()
                .videoId(videoId)
                .title(snippet.getTitle())
                .youtubeChannelId(snippet.getChannelId())
                .channelTitle(snippet.getChannelTitle())
                .description(snippet.getDescription())
                .broadcastStatus(snippet.getLiveBroadcastContent())
                .thumbnailUrl(resolveThumbnailUrl(snippet))
                .watchUrl(YouTubeWatchUrl.of(videoId));

        if (streamingDetails != null) {
            if (streamingDetails.getActualStartTime() != null) {
                builder.actualStartTime(
                        EpochTimeConverter.toSystemLocalDateTime(streamingDetails.getActualStartTime().getValue()));
            }
            if (streamingDetails.getScheduledStartTime() != null) {
                builder.scheduledStartTime(
                        EpochTimeConverter.toSystemLocalDateTime(streamingDetails.getScheduledStartTime().getValue()));
            }
        }

        return builder.build();
    }

    /**
     * 高解像度のサムネイルを優先して URL を選ぶ。
     *
     * @param snippet 動画のスニペット情報
     * @return サムネイル URL。どれも取得できない場合は {@code null}
     */
    private String resolveThumbnailUrl(VideoSnippet snippet) {
        if (snippet.getThumbnails() == null) {
            return null;
        }
        if (snippet.getThumbnails().getHigh() != null) {
            return snippet.getThumbnails().getHigh().getUrl();
        }
        if (snippet.getThumbnails().getDefault() != null) {
            return snippet.getThumbnails().getDefault().getUrl();
        }
        return null;
    }

    /**
     * API キーが設定されていなければ、API を呼ぶ前に断る。
     *
     * <p>キーが無くても API は呼べてしまい、Google が 403 を返す。呼んでから失敗にすると、
     * 本当の通信の失敗と見分けがつかず、ログにも「失敗しました」が残るため、呼ぶ前に確かめる。
     * 文言は利用者の検索（{@link YouTubeSearchService}）と揃える。
     */
    private void requireApiKey() {
        String apiKey = monitorProperties.youtube().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new YouTubeApiUnavailableException("YouTube の API キーが設定されていません");
        }
    }
}
