package com.example.monitor.util;

import com.example.monitor.entity.OnlineVideo;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * YouTube の動画ページ（{@code /watch?v=}）の HTML から動画の種類を読み取る。
 *
 * <p>YouTube Data API で判定しないのは、収集のたびに呼ぶとクォータを消費するため。
 * 目印は実際の動画ページで確かめたもの（待機所は {@code "isUpcoming":true} と
 * {@code "isLiveContent":true} の両方を持つので、{@code isUpcoming} を先に見る）。
 */
public final class YouTubeVideoKindParser {
    /** {@code LiveStreamDetector} と同じ正規表現。今回は共通化しない（Issue #89 の判断）。 */
    private static final Pattern SCHEDULED_START_TIME = Pattern.compile("\"scheduledStartTime\":\"(\\d+)\"");

    private YouTubeVideoKindParser() {}

    /** 判定結果。{@code scheduledStartTime} は配信予定のときだけ入る。 */
    public record Result(String kind, Instant scheduledStartTime) {}

    /**
     * 目印が一つも無いとき（ログイン画面・同意画面・構造変化など）は null を返す。
     * 投稿動画として返さないのは、判定できなかった動画を投稿済みの段に誤って並べないため。
     */
    public static Result parse(String html) {
        if (html == null) return null;
        if (html.contains("\"isUpcoming\":true")) {
            var matcher = SCHEDULED_START_TIME.matcher(html);
            return new Result(OnlineVideo.KIND_UPCOMING,
                    matcher.find() ? Instant.ofEpochSecond(Long.parseLong(matcher.group(1))) : null);
        }
        if (html.contains("\"isLiveContent\":true")) return new Result(OnlineVideo.KIND_STREAM, null);
        if (html.contains("\"isLiveContent\":false")) return new Result(OnlineVideo.KIND_UPLOAD, null);
        return null;
    }
}
