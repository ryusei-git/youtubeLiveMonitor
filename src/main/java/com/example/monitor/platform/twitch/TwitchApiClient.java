package com.example.monitor.platform.twitch;

import com.example.monitor.config.MonitorProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Twitch Helix API の呼び出し窓口。
 *
 * <h2>YouTube との決定的な違い</h2>
 * YouTube Data API は 1 日あたりのクォータ（既定 10,000）が厳しく、そのため本アプリは
 * 配信中判定を公式 API ではなく HTML 解析で行っている。対して Twitch は
 * <b>1 リクエストで最大 100 チャンネルをまとめて問い合わせられる</b>うえ、
 * レート上限も分単位のポイント制でずっと緩い。結果として Twitch では
 * <b>公式 API をそのまま毎サイクル呼べる</b>（HTML 解析のような回避策が要らない）。
 *
 * <h2>オフラインの表し方に注意</h2>
 * {@code /helix/streams} は<b>配信中のチャンネルしか返さない</b>。
 * 問い合わせた ID が応答に含まれていなければ「配信していない」を意味する。
 * ただし<b>通信自体が失敗した場合と混同してはならない</b>。このクラスは失敗時に
 * 例外を投げ、呼び出し側（{@code TwitchStreamPlatform}）が「判定できなかった」に倒す。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TwitchApiClient {

    /** 配信状態を問い合わせるエンドポイント。 */
    private static final String STREAMS_URL = "https://api.twitch.tv/helix/streams";

    /** ユーザー情報を問い合わせるエンドポイント。 */
    private static final String USERS_URL = "https://api.twitch.tv/helix/users";

    /**
     * 1 リクエストで問い合わせられるチャンネル数の上限。Twitch API の仕様値。
     *
     * <p>これを超える場合はこのクラスの内部で分割して呼ぶため、呼び出し側は件数を気にしなくてよい。
     */
    private static final int MAX_IDS_PER_REQUEST = 100;

    /** 応答待ち上限。監視ループ全体を止めないために設ける。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** 残りリクエスト数がこれを下回ったら警告する。 */
    private static final int RATE_LIMIT_WARNING_THRESHOLD = 50;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MonitorProperties monitorProperties;
    private final TwitchTokenProvider twitchTokenProvider;

    /**
     * ユーザーの最近の VOD（配信のアーカイブ）を新しい順に返す。
     *
     * <p>配信IDとVOD IDは別物なので、APIが返す関連付けと視聴URLを保存する。
     *
     * <p>失敗は例外で知らせる。空のリストを返すのは VOD が無いときだけ。
     *
     * @param userId 数値の Twitch ユーザー ID
     * @return 最近の VOD（最大 100 件）
     * @throws IllegalArgumentException ユーザー ID が数値でない場合
     * @throws IllegalStateException 設定が未完了、HTTP 200 以外の応答（401 はトークンを取り直して 1 回だけ再試行する）、
     *                               通信の失敗・中断、または応答を解釈できなかった場合
     * @throws java.time.format.DateTimeParseException VOD の {@code published_at} を読めなかった場合
     */
    public java.util.List<com.example.monitor.dto.OnlineVideoCandidate> fetchRecentVideos(String userId) {
        if (!userId.matches("[0-9]+")) throw new IllegalArgumentException("TwitchユーザーIDが不正です");
        var body = get("https://api.twitch.tv/helix/videos?user_id=" + userId + "&first=100&sort=time");
        var result = new java.util.ArrayList<com.example.monitor.dto.OnlineVideoCandidate>();
        for (var item : body.path("data")) {
            if (!userId.equals(item.path("user_id").asText())) continue;
            String stream = item.path("stream_id").asText("");
            String id = item.path("id").asText();
            if (!id.matches("[0-9]+")) continue;
            String key = "TWITCH" + "_" + (stream.isBlank() ? "video_" + id : "stream_" + stream);
            result.add(new com.example.monitor.dto.OnlineVideoCandidate(key, item.path("title").asText(),
                    "https://www.twitch.tv/videos/" + id,
                    item.path("thumbnail_url").asText().replace("%{width}", "640").replace("%{height}", "360"),
                    java.time.Instant.parse(item.path("published_at").asText())));
        }
        return result;
    }

    /**
     * 指定したユーザー ID のうち、<b>現在配信中のものだけ</b>を返す。
     *
     * <p>応答に含まれない ID は配信していない。100 件を超える場合は内部で分割して問い合わせる。
     *
     * @param userIds 問い合わせるユーザー ID
     * @return 配信中の配信情報。1 件も配信中でなければ空リスト
     * @throws IllegalStateException 設定が未完了、または問い合わせに失敗した場合
     */
    public List<TwitchStream> fetchLiveStreams(List<String> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        List<TwitchStream> streams = new ArrayList<>();
        for (int start = 0; start < userIds.size(); start += MAX_IDS_PER_REQUEST) {
            List<String> chunk = userIds.subList(
                    start, Math.min(start + MAX_IDS_PER_REQUEST, userIds.size()));
            String query = buildRepeatedQuery("user_id", chunk);
            JsonNode body = get(STREAMS_URL + "?" + query);
            for (JsonNode item : body.path("data")) {
                streams.add(toStream(item));
            }
        }
        return streams;
    }

    /**
     * ログイン名からユーザー情報を引く。チャンネル登録時に、変更されうるログイン名を
     * 不変のユーザー ID へ解決するために使う。
     *
     * @param login ログイン名（URL に現れる名前。{@code @} は付けない）
     * @return 見つかったユーザー。存在しなければ {@link Optional#empty()}
     * @throws IllegalStateException 設定が未完了、または問い合わせに失敗した場合
     */
    public Optional<TwitchUser> findUserByLogin(String login) {
        JsonNode body = get(USERS_URL + "?login=" + encode(login));
        JsonNode data = body.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return Optional.empty();
        }
        JsonNode item = data.get(0);
        return Optional.of(new TwitchUser(
                item.path("id").asText(),
                item.path("login").asText(),
                item.path("display_name").asText(),
                item.path("profile_image_url").asText(null)));
    }

    /**
     * ユーザー ID からユーザー情報をまとめて引く。改名されうるログイン名を取り直すために使う。
     *
     * <p>100 件を超える場合は内部で分割して問い合わせる。存在しない ID は応答に含まれない。
     *
     * @param ids ユーザー ID
     * @return 見つかったユーザー。1 件も無ければ空リスト
     * @throws IllegalStateException 設定が未完了、または問い合わせに失敗した場合
     */
    public List<TwitchUser> findUsersByIds(List<String> ids) {
        List<TwitchUser> users = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += MAX_IDS_PER_REQUEST) {
            List<String> chunk = ids.subList(start, Math.min(start + MAX_IDS_PER_REQUEST, ids.size()));
            JsonNode body = get(USERS_URL + "?" + buildRepeatedQuery("id", chunk));
            for (JsonNode item : body.path("data")) {
                users.add(new TwitchUser(
                        item.path("id").asText(),
                        item.path("login").asText(),
                        item.path("display_name").asText(),
                        item.path("profile_image_url").asText(null)));
            }
        }
        return users;
    }

    /**
     * 同じ名前のクエリパラメータを値の数だけ並べた文字列を組み立てる。
     *
     * <p>Twitch API は {@code user_id=1&user_id=2&...} の形で複数指定を受け付ける。
     *
     * @param name   パラメータ名
     * @param values 値
     * @return クエリ文字列（先頭に {@code ?} は付けない）
     */
    private String buildRepeatedQuery(String name, List<String> values) {
        return values.stream()
                .map(value -> name + "=" + encode(value))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    /**
     * Helix API を GET で呼び出して応答の JSON を返す。
     *
     * <p><b>401 が返った場合はトークンを捨てて 1 度だけ再試行する。</b>
     * Twitch 側でトークンが失効させられることがあり、有効期限の管理だけでは
     * 取りこぼすため（{@link TwitchTokenProvider} の JavaDoc 参照）。
     *
     * @param url 呼び出す URL（クエリ込み）
     * @return 応答の JSON
     * @throws IllegalStateException 問い合わせに失敗した場合
     */
    private JsonNode get(String url) {
        HttpResponse<String> response = send(url);

        if (response.statusCode() == 401) {
            log.info("Twitch から 401 が返ったため、トークンを取り直して再試行します");
            twitchTokenProvider.invalidate();
            response = send(url);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Twitch API の呼び出しに失敗しました（HTTP " + response.statusCode() + "）");
        }

        warnIfRateLimitLow(response);

        try {
            return objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new IllegalStateException("Twitch API の応答を解釈できませんでした: " + e.getMessage());
        }
    }

    /**
     * 認証情報を付けてリクエストを送る。
     *
     * @param url 呼び出す URL
     * @return 応答
     * @throws IllegalStateException 通信に失敗した場合
     */
    private HttpResponse<String> send(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Client-Id", monitorProperties.twitch().clientId())
                .header("Authorization", "Bearer " + twitchTokenProvider.accessToken())
                .GET()
                .build();

        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Twitch API への通信に失敗しました: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Twitch API の呼び出しが中断されました");
        }
    }

    /**
     * 残りリクエスト数が少なくなっていれば警告する。
     *
     * <p>Twitch は残量を応答ヘッダーで教えてくれる。YouTube のクォータと違って
     * 分単位で回復するため通常は枯渇しないが、想定外の呼び出し増加に気づけるようにしておく。
     *
     * @param response API の応答
     */
    private void warnIfRateLimitLow(HttpResponse<String> response) {
        response.headers().firstValue("Ratelimit-Remaining").ifPresent(remaining -> {
            try {
                int value = Integer.parseInt(remaining);
                if (value < RATE_LIMIT_WARNING_THRESHOLD) {
                    log.warn("Twitch API の残りリクエスト数が少なくなっています: 残り={}", value);
                }
            } catch (NumberFormatException e) {
                // ヘッダーの形式が変わっただけで監視を止める理由にはならない
                log.debug("Ratelimit-Remaining を解釈できませんでした: {}", remaining);
            }
        });
    }

    /**
     * 応答の 1 件を {@link TwitchStream} に詰め替える。
     *
     * @param item 応答の {@code data} 配列の 1 要素
     * @return 詰め替えた配信情報
     */
    private TwitchStream toStream(JsonNode item) {
        return new TwitchStream(
                item.path("id").asText(),
                item.path("user_id").asText(),
                item.path("user_login").asText(),
                item.path("user_name").asText(),
                item.path("title").asText(),
                item.path("game_name").asText(""),
                item.path("viewer_count").asInt(0),
                item.path("thumbnail_url").asText(null),
                item.path("started_at").asText(null));
    }

    /**
     * クエリパラメータとして安全な形に符号化する。
     *
     * @param value 符号化したい値
     * @return 符号化した文字列
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
