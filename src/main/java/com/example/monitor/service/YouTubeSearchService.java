package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.YouTubeSearchRequest;
import com.example.monitor.dto.YouTubeSearchResponse;
import com.example.monitor.dto.YouTubeVideoResponse;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.exception.SearchQuotaExceededException;
import com.example.monitor.exception.YouTubeApiUnavailableException;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.TitleKeywordMatcher;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.Thumbnail;
import com.google.api.services.youtube.model.ThumbnailDetails;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 利用者の YouTube 検索と、視聴画面の動画の詳細を受け持つ。
 *
 * <h2>6 時間の使い回し</h2>
 * 検索は 1 人 1 日 10 回しか使えない。同じ条件で開き直したり、このサービスの条件だけを
 * 変えたりするたびに回数が減ると、実質の回数がもっと少なくなる。そこで
 * 「公式の条件と {@code pageToken}」の組ごとに、API の結果をメモリに 6 時間持つ。
 * このサービスの条件はその結果にあとから当てるので、変えても回数を使わない。
 * DB に保存しないのは、API の値を 30 日以内に取り直すか消す規約（III.E.4.d）より十分短く、
 * 再起動で消えても困らないため。
 *
 * <p>ただし「ライブ中」（{@code eventType=live}）の検索は 15 分にする。配信は数時間で終わるため、
 * 6 時間使い回すと終わった配信に「ライブ」の札が付き、今配信している動画を探すという目的に合わない。
 * 15 分を過ぎて探し直す（視聴画面から戻るのを含む）と回数を使うが、古い結果を黙って出すよりよい。
 * その利用者の今日の残りが 0 回のときは取り直せないので、6 時間以内の古い結果を返す
 * （上限の 429 で何も出ないより、取得時刻の付いた古い結果の方が役に立つ）。
 * どちらの場合も、応答の {@code fetchedAt} に取った元の時刻を入れ、画面が「何分前に取ったか」を出す。
 *
 * <h2>同じ条件の検索が重なったら、先の読み込みを待つ</h2>
 * 使い回しは結果を入れてから効くので、1 本目の応答が返る前に同じ条件の 2 本目が来ると、両方が外れて回数を 2 回使う
 * （Enter の連打や、戻る・進むで起きる）。そこで鍵ごとに読み込み中の印を置き、後の方は先の読み込みが終わるまで
 * 待ってから使い回しを見直す。先の読み込みが失敗したときは後の方が自分の回数で読み直す（失敗の理由がその人の
 * 上限のこともあり、別の利用者に引き継ぐと、回数が残っている人まで 429 になるため）。
 *
 * <h2>足りないときに次のページを 1 回だけ読む</h2>
 * このサービスの条件で絞ると、50 件が数件になることがある。20 件を下回ったら
 * 次のページを 1 回だけ読む（回数を使う）。何ページも読むと 1 回の操作で回数を使い切るため 1 回まで。
 *
 * <h2>印は毎回 DB から付け直す</h2>
 * 「監視中」「保存済み」は、使い回した結果でも今の DB の状態を出す。まとめて 2 回の問い合わせで引く。
 *
 * <h2>エラーのログに例外を渡さない</h2>
 * {@link GoogleJsonResponseException} のメッセージには、API キーの付いた要求の URL が入りうる。
 * ログには状態コードと理由だけを出す。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class YouTubeSearchService {

    /** 検索結果を使い回す時間。 */
    private static final Duration SEARCH_CACHE_TTL = Duration.ofHours(6);
    /**
     * 「ライブ中」（{@code eventType=live}）の検索結果を使い回す時間。配信は数時間で終わるため、
     * 6 時間使い回すと終わった配信に「ライブ」の札が付いたまま出る。探し直すと回数を使うが、
     * 「今配信している動画を探す」検索で古い結果を黙って返すより、15 分ごとに取り直す方が目的に合う。
     */
    private static final Duration LIVE_SEARCH_CACHE_TTL = Duration.ofMinutes(15);
    /** 動画の詳細を使い回す時間。視聴画面は再生回数などが動くので検索より短くする。 */
    private static final Duration VIDEO_CACHE_TTL = Duration.ofHours(1);
    /** この件数を下回ったら次のページを読む。 */
    private static final int REFILL_THRESHOLD = 20;
    /** これ以下の長さをショートとみなす（YouTube のショートは 3 分まで）。 */
    private static final long SHORTS_MAX_SECONDS = 180;
    /** 検索結果に載せる説明文の長さ（文字）。全文は視聴画面の詳細で返す。 */
    private static final int LIST_DESCRIPTION_LENGTH = 200;
    /** 「保存済み」とみなす録画の状態（再生できるもの）。 */
    private static final List<RecordingStatus> PLAYABLE = List.of(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL);
    /** 動画 ID の形。形が違えば API を呼ばずに 404 にする。 */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final YouTubeSearchClient client;
    private final YouTubeSearchBudget budget;
    private final MonitoredChannelRepository monitoredChannelRepository;
    private final RecordingRepository recordingRepository;
    private final MonitorProperties monitorProperties;

    /** 「公式の条件と pageToken」ごとの検索結果（印は付けていない）。 */
    private final Map<List<String>, Cached<Page>> searchCache = new ConcurrentHashMap<>();
    /**
     * 読み込み中の鍵と、その読み込みの終わりを知らせる印。同じ鍵の検索が同時に来たとき、後の方を待たせて使い回しに当てる。
     * 印は成否に関わらず正常に完了させる（失敗を待っていた側に引き継がないため）。
     */
    private final Map<List<String>, CompletableFuture<Void>> loading = new ConcurrentHashMap<>();
    /** 動画 ID ごとの詳細（印は付けていない）。 */
    private final Map<String, Cached<YouTubeVideoResponse>> videoCache = new ConcurrentHashMap<>();

    /**
     * 検索する。
     *
     * @param request  条件
     * @param username 検索する利用者のログイン ID（回数を数えるため）
     * @return 結果と今日の残り
     * @throws IllegalArgumentException      条件が誤っている場合
     * @throws SearchQuotaExceededException  検索の上限に達した場合
     * @throws YouTubeApiUnavailableException API キーが無い・API が失敗した場合
     */
    public YouTubeSearchResponse search(YouTubeSearchRequest request, String username) {
        validate(request);
        Instant now = Instant.now();
        Page first = loadPage(request, request.pageToken(), username);
        List<YouTubeVideoResponse> items = new ArrayList<>(filter(request, mark(first.items()), now));
        String nextPageToken = first.nextPageToken();

        if (request.hasServiceFilters() && items.size() < REFILL_THRESHOLD && nextPageToken != null) {
            try {
                Page second = loadPage(request, nextPageToken, username);
                Set<String> seen = items.stream().map(YouTubeVideoResponse::videoId).collect(Collectors.toSet());
                filter(request, mark(second.items()), now).stream()
                        .filter(item -> seen.add(item.videoId()))
                        .forEach(items::add);
                nextPageToken = second.nextPageToken();
            } catch (SearchQuotaExceededException e) {
                // 続きを読めないだけなので、1 ページ目の結果は返す
                log.info("検索の上限のため次のページを読みませんでした: user={}", username);
            }
        }
        return new YouTubeSearchResponse(items, nextPageToken, request.hasServiceFilters(),
                first.fetchedAt(), budget.status(username));
    }

    /**
     * 視聴画面のために動画 1 件の詳細を返す。検索の回数は使わない。
     *
     * @param videoId 動画 ID
     * @return 詳細（説明文の全文とタグを含む）。無ければ空
     * @throws YouTubeApiUnavailableException API キーが無い・API が失敗した場合
     */
    public Optional<YouTubeVideoResponse> findVideo(String videoId) {
        if (!VIDEO_ID.matcher(videoId).matches()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Cached<YouTubeVideoResponse> cached = videoCache.get(videoId);
        YouTubeVideoResponse video;
        if (cached != null && cached.isFresh(now, VIDEO_CACHE_TTL)) {
            video = cached.value();
        } else {
            requireApiKey();
            List<YouTubeVideoResponse> found = fetchDetails(List.of(videoId), false, true);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            video = found.get(0);
            videoCache.values().removeIf(entry -> !entry.isFresh(now, VIDEO_CACHE_TTL));
            videoCache.put(videoId, new Cached<>(video, now));
        }
        return Optional.of(mark(List.of(video)).get(0));
    }

    private void validate(YouTubeSearchRequest request) {
        if (isBlank(request.q()) && isBlank(request.channelId())) {
            throw new IllegalArgumentException("検索語かチャンネル ID を指定してください");
        }
        requireInstant("publishedAfter", request.publishedAfter());
        requireInstant("publishedBefore", request.publishedBefore());
    }

    private static void requireInstant(String name, String value) {
        if (value == null) {
            return;
        }
        try {
            Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " は ISO-8601 の日時（例: 2026-09-01T00:00:00Z）で指定してください");
        }
    }

    private void requireApiKey() {
        if (isBlank(monitorProperties.youtube().apiKey())) {
            throw new YouTubeApiUnavailableException("YouTube の API キーが設定されていません");
        }
    }

    /**
     * 1 ページを読む。使い回せるならそれを返し、無ければ回数を使って API を呼ぶ。
     * 同じ鍵を読み込み中なら、その終わりを待ってから使い回しを見直す。
     */
    private Page loadPage(YouTubeSearchRequest request, String pageToken, String username) {
        List<String> key = cacheKey(request, pageToken);
        while (true) {
            Page cached = freshPage(key, request, username);
            if (cached != null) {
                return cached;
            }
            CompletableFuture<Void> mine = new CompletableFuture<>();
            CompletableFuture<Void> running = loading.putIfAbsent(key, mine);
            if (running != null) {
                // 先に来た同じ条件の読み込みを待つ。成功していれば次の周で使い回しに当たる。
                // 失敗していれば、この要求が自分の回数で読み直す
                running.join();
                continue;
            }
            try {
                // 使い回しを見てから印を置くまでの間に、先の読み込みが終わって入れていた場合
                cached = freshPage(key, request, username);
                if (cached != null) {
                    return cached;
                }
                return fetchPage(request, pageToken, username, key);
            } finally {
                // 先に外してから知らせる（逆だと、起きた側が完了済みの古い印を拾って空回りする）
                loading.remove(key, mine);
                mine.complete(null);
            }
        }
    }

    /** 使い回せる 1 ページを返す。無いか古ければ {@code null}。 */
    private Page freshPage(List<String> key, YouTubeSearchRequest request, String username) {
        Cached<Page> cached = searchCache.get(key);
        return cached != null && cached.isFresh(Instant.now(), searchCacheTtl(request, username)) ? cached.value() : null;
    }

    /** 回数を使って API を呼び、結果を使い回しに入れる。 */
    private Page fetchPage(YouTubeSearchRequest request, String pageToken, String username, List<String> key) {
        Instant now = Instant.now();
        requireApiKey();
        budget.acquireForUser(username);
        YouTubeSearchClient.SearchPage found = call(() -> client.searchVideoIds(request, pageToken), true);
        Page page = new Page(fetchDetails(found.videoIds(), true, false), found.nextPageToken(), now);
        // 消すのは一番長い時間（6 時間）を過ぎたものだけ。15 分を過ぎた「ライブ中」の結果も、残りが 0 回の利用者に返すので残す
        searchCache.values().removeIf(entry -> !entry.isFresh(now, SEARCH_CACHE_TTL));
        searchCache.put(key, new Cached<>(page, now));
        return page;
    }

    /**
     * 使い回しの鍵。公式の条件だけから作る（このサービスの条件を変えても同じ鍵になるように）。
     * {@code safeSearch} は既定値に寄せ、省略と {@code moderate} を同じ鍵にする。
     */
    private static List<String> cacheKey(YouTubeSearchRequest r, String pageToken) {
        return Arrays.asList(r.q(), r.order(), r.publishedAfter(), r.publishedBefore(), r.duration(),
                r.eventType(), r.channelId(), r.categoryId(), r.definition(), r.caption(), r.license(),
                r.safeSearch() == null ? "moderate" : r.safeSearch(), pageToken);
    }

    /**
     * 検索結果を使い回す時間を返す。「ライブ中」の検索だけ短くする（{@code LIVE_SEARCH_CACHE_TTL}）。
     * ただし、その利用者の今日の残りが 0 回なら 6 時間のままにする。取り直そうとしても上限の 429 になり
     * 何も出せないので、取得時刻の付いた 6 時間以内の古い結果を返す方がよいため。
     */
    private Duration searchCacheTtl(YouTubeSearchRequest request, String username) {
        if (!"live".equals(request.eventType())) {
            return SEARCH_CACHE_TTL;
        }
        return budget.status(username).userRemaining() > 0 ? LIVE_SEARCH_CACHE_TTL : SEARCH_CACHE_TTL;
    }

    /**
     * 動画とチャンネルの情報を 1 回ずつまとめて取り、渡した順に並べて返す（見つからない動画は落とす）。
     */
    private List<YouTubeVideoResponse> fetchDetails(List<String> videoIds, boolean inSearch, boolean full) {
        List<Video> videos = call(() -> client.fetchVideos(videoIds), inSearch);
        Set<String> channelIds = videos.stream()
                .map(video -> video.getSnippet().getChannelId())
                .collect(Collectors.toSet());
        Map<String, Channel> channels = call(() -> client.fetchChannels(channelIds), inSearch).stream()
                .collect(Collectors.toMap(Channel::getId, Function.identity(), (a, b) -> a));
        Map<String, Video> byId = videos.stream()
                .collect(Collectors.toMap(Video::getId, Function.identity(), (a, b) -> a));
        return videoIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(video -> toResponse(video, channels.get(video.getSnippet().getChannelId()), full))
                .toList();
    }

    /**
     * API の失敗を、利用者に返す例外へ読み替える。
     *
     * <p>403 {@code quotaExceeded} は YouTube 側で使い切ったということなので、こちらの数えた回数が
     * 残っていてもその日の検索を止める。400 の {@code invalid...} は条件の誤りなので 400 にする。
     * それ以外（キーの誤りを含む）は利用者には直せないので 503 にする。
     */
    private <T> T call(ApiCall<T> apiCall, boolean inSearch) {
        try {
            return apiCall.run();
        } catch (GoogleJsonResponseException e) {
            String reason = reasonOf(e);
            log.warn("YouTube API が失敗しました: status={}, reason={}", e.getStatusCode(), reason);
            if (e.getStatusCode() == 403 && "quotaExceeded".equals(reason)) {
                budget.markExhausted();
                if (inSearch) {
                    throw new SearchQuotaExceededException();
                }
                throw new YouTubeApiUnavailableException("YouTube API の本日の上限に達しました");
            }
            if (e.getStatusCode() == 400 && reason != null && reason.startsWith("invalid")) {
                throw new IllegalArgumentException("YouTube が条件を受け付けませんでした（" + reason + "）");
            }
            throw new YouTubeApiUnavailableException("YouTube API の呼び出しに失敗しました");
        } catch (IOException e) {
            log.warn("YouTube API に接続できませんでした: type={}", e.getClass().getSimpleName());
            throw new YouTubeApiUnavailableException("YouTube API の呼び出しに失敗しました");
        }
    }

    private static String reasonOf(GoogleJsonResponseException e) {
        GoogleJsonError details = e.getDetails();
        if (details == null || details.getErrors() == null || details.getErrors().isEmpty()) {
            return null;
        }
        return details.getErrors().get(0).getReason();
    }

    /**
     * 「監視中」と「保存済み」の印を DB の今の状態で付ける。件数に関わらず問い合わせは 2 回。
     */
    private List<YouTubeVideoResponse> mark(List<YouTubeVideoResponse> items) {
        if (items.isEmpty()) {
            return items;
        }
        Set<String> channelIds = items.stream().map(YouTubeVideoResponse::channelId).collect(Collectors.toSet());
        Set<String> videoIds = items.stream().map(YouTubeVideoResponse::videoId).collect(Collectors.toSet());
        Set<String> registered = new HashSet<>(monitoredChannelRepository.findRegisteredYoutubeChannelIds(channelIds));
        Map<String, Long> recordingIds = recordingRepository.findByVideoIdInAndStatusIn(videoIds, PLAYABLE).stream()
                .collect(Collectors.toMap(Recording::getVideoId, Recording::getId, (a, b) -> a));
        return items.stream()
                .map(item -> item.withMarks(registered.contains(item.channelId()), recordingIds.get(item.videoId())))
                .toList();
    }

    private static List<YouTubeVideoResponse> filter(YouTubeSearchRequest request, List<YouTubeVideoResponse> items,
                                                     Instant now) {
        if (!request.hasServiceFilters()) {
            return items;
        }
        return items.stream().filter(item -> matches(request, item, now)).toList();
    }

    /**
     * このサービスの条件に合うかを返す。
     *
     * <p>長さの条件（ショートを除く、を含む）は、長さのある動画（{@code none}）にだけ当てる。
     * 配信中・予約枠の長さは 0 で、当てるとすべて落ちてしまうため。
     * 非公開の値（高評価・登録者数）は、下限の条件では落とし、上限の条件では残す
     * （「登録者の少ないチャンネル」を探すときに、非公開のチャンネルを取りこぼさないため）。
     */
    private static boolean matches(YouTubeSearchRequest r, YouTubeVideoResponse v, Instant now) {
        if ("none".equals(v.liveBroadcastContent())) {
            if (r.minDurationSec() != null && v.durationSeconds() < r.minDurationSec()) {
                return false;
            }
            if (r.maxDurationSec() != null && v.durationSeconds() > r.maxDurationSec()) {
                return false;
            }
            if (Boolean.TRUE.equals(r.excludeShorts()) && v.durationSeconds() <= SHORTS_MAX_SECONDS) {
                return false;
            }
        }
        if (r.minViews() != null && (v.viewCount() == null || v.viewCount() < r.minViews())) {
            return false;
        }
        if (r.maxViews() != null && v.viewCount() != null && v.viewCount() > r.maxViews()) {
            return false;
        }
        if (r.minLikes() != null && (v.likeCount() == null || v.likeCount() < r.minLikes())) {
            return false;
        }
        if (r.maxSubscribers() != null && v.channelSubscriberCount() != null
                && v.channelSubscriberCount() > r.maxSubscribers()) {
            return false;
        }
        if (r.maxChannelVideos() != null && v.channelVideoCount() != null
                && v.channelVideoCount() > r.maxChannelVideos()) {
            return false;
        }
        if (!TitleKeywordMatcher.matches(r.titleIncludes(), v.title(), null)) {
            return false;
        }
        if (!isBlank(r.titleExcludes()) && TitleKeywordMatcher.matches(r.titleExcludes(), v.title(), null)) {
            return false;
        }
        if (r.withinHours() != null && v.publishedAt() != null
                && v.publishedAt().isBefore(now.minus(Duration.ofHours(r.withinHours())))) {
            return false;
        }
        if (Boolean.TRUE.equals(r.onlyRegistered()) && !v.registered()) {
            return false;
        }
        if (Boolean.TRUE.equals(r.excludeRegistered()) && v.registered()) {
            return false;
        }
        return !(Boolean.TRUE.equals(r.excludeSaved()) && v.saved());
    }

    private static YouTubeVideoResponse toResponse(Video video, Channel channel, boolean full) {
        VideoSnippet snippet = video.getSnippet();
        var statistics = video.getStatistics();
        var liveDetails = video.getLiveStreamingDetails();
        var channelStatistics = channel == null ? null : channel.getStatistics();
        boolean subscriberHidden = channelStatistics != null
                && Boolean.TRUE.equals(channelStatistics.getHiddenSubscriberCount());
        String description = snippet.getDescription() == null ? "" : snippet.getDescription();
        return new YouTubeVideoResponse(
                video.getId(),
                snippet.getTitle(),
                full ? description : head(description, LIST_DESCRIPTION_LENGTH),
                toInstant(snippet.getPublishedAt()),
                thumbnailUrl(snippet.getThumbnails(), true),
                durationSeconds(video.getContentDetails() == null ? null : video.getContentDetails().getDuration()),
                statistics == null ? null : toLong(statistics.getViewCount()),
                statistics == null ? null : toLong(statistics.getLikeCount()),
                statistics == null ? null : toLong(statistics.getCommentCount()),
                snippet.getLiveBroadcastContent(),
                liveDetails == null ? null : toInstant(liveDetails.getScheduledStartTime()),
                video.getStatus() != null && Boolean.TRUE.equals(video.getStatus().getMadeForKids()),
                snippet.getChannelId(),
                channel == null ? snippet.getChannelTitle() : channel.getSnippet().getTitle(),
                channel == null ? null : thumbnailUrl(channel.getSnippet().getThumbnails(), false),
                channelStatistics == null || subscriberHidden ? null : toLong(channelStatistics.getSubscriberCount()),
                subscriberHidden,
                channelStatistics == null ? null : toLong(channelStatistics.getVideoCount()),
                false,
                false,
                null,
                full ? (snippet.getTags() == null ? List.of() : snippet.getTags()) : null);
    }

    /**
     * サムネイルの URL を選ぶ（URL は API の値のまま）。動画は大きい方、チャンネルのアイコンは
     * 小さい方を優先する（アイコンの high は 800px あり、一覧の小さな丸に出すには重いため）。
     */
    private static String thumbnailUrl(ThumbnailDetails thumbnails, boolean largest) {
        if (thumbnails == null) {
            return null;
        }
        Stream<Thumbnail> candidates = largest
                ? Stream.of(thumbnails.getHigh(), thumbnails.getMedium(), thumbnails.getDefault())
                : Stream.of(thumbnails.getDefault(), thumbnails.getMedium(), thumbnails.getHigh());
        return candidates
                .filter(Objects::nonNull)
                .map(Thumbnail::getUrl)
                .findFirst()
                .orElse(null);
    }

    /** ISO-8601 の長さ（{@code PT1H2M3S}）を秒にする。配信中の {@code P0D} や読めない値は 0。 */
    private static long durationSeconds(String isoDuration) {
        if (isoDuration == null) {
            return 0;
        }
        try {
            return Duration.parse(isoDuration).getSeconds();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /** 先頭から指定の文字数だけ返す（サロゲートペアを割らないようにコードポイントで数える）。 */
    private static String head(String text, int codePoints) {
        if (text.codePointCount(0, text.length()) <= codePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, codePoints));
    }

    private static Instant toInstant(DateTime dateTime) {
        return dateTime == null ? null : Instant.ofEpochMilli(dateTime.getValue());
    }

    private static Long toLong(BigInteger value) {
        return value == null ? null : value.longValue();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** {@link IOException} を投げる API 呼び出し。 */
    @FunctionalInterface
    private interface ApiCall<T> {
        T run() throws IOException;
    }

    /**
     * 使い回す値と、それを取った時刻。
     *
     * @param value     値
     * @param fetchedAt 取った時刻
     */
    private record Cached<T>(T value, Instant fetchedAt) {
        boolean isFresh(Instant now, Duration ttl) {
            return fetchedAt.plus(ttl).isAfter(now);
        }
    }

    /**
     * 検索 1 ページぶんの結果（印は付けていない）。
     *
     * @param items         動画（検索の並び順）
     * @param nextPageToken 次のページの印
     * @param fetchedAt     取った時刻
     */
    private record Page(List<YouTubeVideoResponse> items, String nextPageToken, Instant fetchedAt) {}
}
