package com.example.monitor.service;

import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.util.EpochTimeConverter;
import com.example.monitor.util.HttpResponseBodies;
import com.example.monitor.util.YouTubeWatchUrl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * チャンネルが今まさに配信中かどうかを、YouTube Data API のクォータを消費せずに検知する。
 *
 * <h2>なぜ公式 API を使わないのか</h2>
 * 「このチャンネルが今配信中か」を公式 API で調べるには {@code search.list} を使うしかなく、
 * 1 回あたり 100 クォータを消費する。1 日の上限は既定で 10,000 なので、
 * 10 チャンネルを 1 時間おきに調べるだけで上限を超えてしまう（10 × 24 × 100 = 24,000）。
 *
 * <h2>採用した手法</h2>
 * {@code https://www.youtube.com/channel/{channelId}/live} を通常の HTTP GET で取得し、
 * レスポンス HTML の {@code <link rel="canonical">} が指す先を見る。
 * <ul>
 *   <li>配信中 … {@code <link rel="canonical" href="https://www.youtube.com/watch?v=VIDEO_ID">}</li>
 *   <li>非配信 … {@code <link rel="canonical" href="https://www.youtube.com/channel/CHANNEL_ID/live">}</li>
 * </ul>
 * つまり canonical が動画ページを指していれば配信中で、その URL から動画 ID まで同時に得られる。
 *
 * <h2>「待機所」（配信開始前の予約枠）を配信中と誤判定しないための追加チェック</h2>
 * canonical だけで判定すると、<b>配信がまだ始まっていない予約枠（いわゆる「待機所」）まで
 * 配信中と誤検知してしまう</b>（実際に発生した：配信開始の146日も前から「配信中」と判定され、
 * Discord に誤通知が飛んだ）。YouTube は配信中の動画だけでなく、開始前の待機所ページにも
 * {@code /live} の canonical を向けるため。これを見分けるため、レスポンス HTML に埋め込まれた
 * JSON 内の {@code "isUpcoming":true} の有無を追加で確認している
 * （待機所のページにだけ含まれ、実際に配信中のページには含まれないことを実機で確認済み）。
 *
 * <h2>この手法のリスク</h2>
 * YouTube が公式に保証した仕様ではないため、HTML 構造が変われば動かなくなる。
 * そのため想定と違う応答を受け取った場合は WARN ログを出したうえで
 * {@link LiveStreamDetection#failed()}（判定できなかった）を返す。誤って通知を飛ばすより、
 * 見逃して次のサイクルに任せる方が実害が小さいという判断。
 * <b>このとき「配信していない」（{@link LiveStreamDetection#notLive()}）とは必ず区別する。</b>
 * 両者を同じ結果にしてしまうと、この非公式な手法が壊れてアプリが実質停止していても
 * 画面上は平常運転に見えてしまうため（{@link LiveStreamDetection} の JavaDoc 参照）。
 *
 * <h2>配信タイトルも同じ HTML から取得する</h2>
 * 同じレスポンスの {@code <meta name="title" content="...">} に配信タイトルが含まれているため、
 * canonical タグと合わせて追加の通信なしで取得している。録画対象をタイトルで絞り込む
 * （{@link com.example.monitor.entity.MonitoredChannel#matchesFilter(String, String)}）ために
 * 使う。YouTube Data API の {@code videos.list}（クォータ1消費）でも取得できるが、
 * 毎サイクル呼ぶにはクォータが厳しいため、こちらを優先している。
 *
 * <h2>HTML は DOM にせず、3 つのタグだけを正規表現で読む</h2>
 * 応答は 1 件 1.2〜1.5MB あり、大半は使わない埋め込みのスクリプト。DOM を作ると、その全体を
 * 5 分ごとにチャンネルの数だけオブジェクトにして捨てることになる（#184）。使うのは canonical・
 * {@code <meta name="title">}・{@code og:image} の 3 つだけなので、YouTube が出力している
 * 属性の並び（{@code <link rel="canonical" href="...">} など）のまま正規表現で読む。
 * 並びが変わって読めなくなっても、canonical が読めなければ「判定できなかった」になり、
 * タイトルとアイコンは欠けるだけなので、誤って通知を飛ばす側には倒れない。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveStreamDetector {

    /** 配信中かどうかを調べるためのチャンネル別 URL。{@code %s} にチャンネル ID が入る。 */
    private static final String LIVE_PAGE_URL_TEMPLATE = "https://www.youtube.com/channel/%s/live";

    /** 配信中かどうかと動画 ID を決める canonical の URL。 */
    private static final Pattern CANONICAL_LINK = Pattern.compile("<link\\s+rel=\"canonical\"\\s+href=\"([^\"]*)\"");

    /** 配信タイトル（動画ページのときだけある）。 */
    private static final Pattern TITLE_META = Pattern.compile("<meta\\s+name=\"title\"\\s+content=\"([^\"]*)\"");

    /** 配信していないとき（チャンネルページが返る）のチャンネルのアイコン。 */
    private static final Pattern OG_IMAGE_META =
            Pattern.compile("<meta\\s+property=\"og:image\"\\s+content=\"([^\"]*)\"");

    /** canonical URL から動画 ID（11 文字固定）を抜き出す正規表現。 */
    private static final Pattern VIDEO_ID_IN_URL = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})");

    /** 待機所ページの JSON に含まれる開始予定時刻（エポック秒）。 */
    private static final Pattern SCHEDULED_START_TIME = Pattern.compile("\"scheduledStartTime\":\"(\\d+)\"");

    /**
     * 動画ページ（配信中・待機所）に埋め込まれた、配信者のアイコン URL。
     * 動画ページの {@code og:image} は動画のサムネイルなので使えない。
     */
    private static final Pattern VIDEO_OWNER_ICON = Pattern.compile(
            "\"videoOwnerRenderer\":\\{\"thumbnail\":\\{\"thumbnails\":\\[\\{\"url\":\"([^\"]+)\"");

    /** アイコン画像のサイズ指定（{@code =s900-} など）。 */
    private static final Pattern ICON_SIZE = Pattern.compile("=s\\d+-");

    /** ブラウザ以外からのアクセスとみなされて簡易版 HTML を返されるのを避けるための User-Agent。 */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /**
     * 配信開始前の「待機所」ページにだけ埋め込まれる JSON の断片。
     * 実際に配信中のページには含まれないことを実機で確認済み（クラスの JavaDoc 参照）。
     */
    private static final String UPCOMING_MARKER = "\"isUpcoming\":true";

    /** 1 チャンネルの応答待ちで監視ループ全体が止まらないよう、応答に上限を設ける。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** {@link com.example.monitor.config.HttpClientConfig} で組み立てられたものが注入される。 */
    private final HttpClient httpClient;

    /**
     * 指定チャンネルの配信状態を調べる。
     *
     * <p>通信エラーや HTML 構造の変化など、判定できなかった場合も例外は投げない
     * （呼び出し側が 1 チャンネルの失敗で止まらないようにするため）。ただし戻り値では
     * 「配信していない」と「判定できなかった」を必ず区別する。
     *
     * @param youtubeChannelId YouTube が発行するチャンネル ID
     * @return 配信中／配信していない／判定できなかった、のいずれかを表す結果
     */
    public LiveStreamDetection detectLiveStream(String youtubeChannelId) {
        String livePageUrl = String.format(LIVE_PAGE_URL_TEMPLATE, youtubeChannelId);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(livePageUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept-Encoding", "gzip")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.warn("配信状態の取得で予期しないステータスを受信しました: status={}, channel={}",
                        response.statusCode(), youtubeChannelId);
                return LiveStreamDetection.failed();
            }

            return extractDetection(HttpResponseBodies.decodeUtf8(response), youtubeChannelId);

        } catch (Exception e) {
            log.warn("配信状態の取得に失敗しました: channel={}, reason={}", youtubeChannelId, e.getMessage());
            return LiveStreamDetection.failed();
        }
    }

    /**
     * ライブページの HTML から canonical URL とタイトルを読み取る。
     *
     * @param html             ライブページのレスポンス本文
     * @param youtubeChannelId ログ出力用のチャンネル ID
     * @return 配信中／配信していない／解析できなかった、のいずれかを表す結果
     */
    private LiveStreamDetection extractDetection(String html, String youtubeChannelId) {
        String canonicalUrl = readAttribute(CANONICAL_LINK, html);

        if (canonicalUrl.isBlank()) {
            // 構造が変わって解析できなくなった可能性があるので、「配信していない」ではなく判定失敗として扱う
            log.warn("canonical タグが見つかりませんでした。YouTube 側の HTML 構造が変わった可能性があります: channel={}",
                    youtubeChannelId);
            return LiveStreamDetection.failed();
        }

        Matcher matcher = VIDEO_ID_IN_URL.matcher(canonicalUrl);
        if (!matcher.find()) {
            log.debug("配信していません: channel={}", youtubeChannelId);
            // 配信していないときはチャンネルページが返るので、og:image がチャンネルのアイコンになる
            return LiveStreamDetection.notLive().withChannelIcon(normalizeIconUrl(readAttribute(OG_IMAGE_META, html)));
        }

        String videoId = matcher.group(1);
        Matcher iconMatcher = VIDEO_OWNER_ICON.matcher(html);
        String channelIconUrl = iconMatcher.find() ? normalizeIconUrl(iconMatcher.group(1)) : null;

        if (html.contains(UPCOMING_MARKER)) {
            Matcher scheduledStartMatcher = SCHEDULED_START_TIME.matcher(html);
            LocalDateTime scheduledStartTime = null;
            if (scheduledStartMatcher.find()) {
                long epochSeconds = Long.parseLong(scheduledStartMatcher.group(1));
                scheduledStartTime = EpochTimeConverter.toSystemLocalDateTime(epochSeconds * 1000);
            } else {
                log.warn("待機所の開始予定時刻が見つかりません。YouTube 側の HTML 構造が変わった可能性があります: channel={}, video={}",
                        youtubeChannelId, videoId);
            }
            String title = readAttribute(TITLE_META, html);
            log.debug("配信はまだ開始していません（待機所）: channel={}, video={}, scheduledStartTime={}",
                    youtubeChannelId, videoId, scheduledStartTime);
            return LiveStreamDetection.upcoming(videoId, title.isBlank() ? null : title,
                    YouTubeWatchUrl.of(videoId), scheduledStartTime).withChannelIcon(channelIconUrl);
        }

        String title = readAttribute(TITLE_META, html);
        log.debug("配信中を検知しました: channel={}, video={}", youtubeChannelId, videoId);
        // カテゴリは null。YouTube には配信ごとにカテゴリを申告する項目が無い
        return LiveStreamDetection.live(
                videoId, title.isBlank() ? null : title, null, YouTubeWatchUrl.of(videoId))
                .withChannelIcon(channelIconUrl);
    }

    /**
     * タグの属性値を、文字参照（{@code &amp;} など）を戻して読む。
     *
     * <p>戻すのは、YouTube がタイトルの {@code &} や {@code "} を {@code &amp;}・{@code &quot;} と
     * 書いて返すため（戻さないと通知や絞り込みにそのまま渡る）。{@code unescapeEntities} の第 2 引数を
     * {@code true} にするのは、属性値の規則（{@code ;} の無い {@code &amp=} などは戻さない）に合わせ、
     * HTML パーサーが属性値を読んだときと同じ文字列にするため。見つからないときに {@code null} ではなく
     * 空文字を返すのは、「タグが無い」と「値が空」をどちらも呼び出し側の {@code isBlank()} で同じに扱うため。
     *
     * @param tag  値を {@code group(1)} に取る、タグの正規表現
     * @param html ライブページのレスポンス本文
     * @return 属性値。タグが見つからなければ空文字
     */
    private static String readAttribute(Pattern tag, String html) {
        Matcher matcher = tag.matcher(html);
        return matcher.find() ? Parser.unescapeEntities(matcher.group(1), true) : "";
    }

    /**
     * 読み取ったアイコン URL を、保存してよい形に揃える。
     *
     * <p>アイコンが読めないことは判定失敗にしない（表示が欠けるだけで、配信の判定には関係ないため）。
     * YouTube のアイコン配信ホスト以外を弾くのは、動画サムネイルなど別の画像を取り違えないため。
     * サイズを {@code s88} に揃えるのは、{@code og:image} は 900px と大きすぎ、
     * {@code videoOwnerRenderer} は 48px と小さすぎるため（表示サイズの 2 倍）。
     *
     * @param url HTML から読み取った URL。空文字の場合もある
     * @return 揃えた URL。アイコンとみなせない場合は {@code null}
     */
    private static String normalizeIconUrl(String url) {
        if (!url.startsWith("https://yt3.ggpht.com/") && !url.startsWith("https://yt3.googleusercontent.com/")) {
            return null;
        }
        return ICON_SIZE.matcher(url).replaceFirst("=s88-");
    }
}
