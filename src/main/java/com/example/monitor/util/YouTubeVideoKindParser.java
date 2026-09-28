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
 *
 * <p>削除された・存在しない動画は、種類の目印が無い代わりに「再生できません」の目印を持つので、
 * 消えた動画（{@link OnlineVideo#KIND_MISSING}）として返す。判定できない（null）のままにすると、
 * 待機所が消されたとき配信予定として残り続けるため。この結果を書くかどうかは呼び出し側が決める。
 */
public final class YouTubeVideoKindParser {
    /** {@code LiveStreamDetector} と同じ正規表現。今回は共通化しない（Issue #89 の判断）。 */
    private static final Pattern SCHEDULED_START_TIME = Pattern.compile("\"scheduledStartTime\":\"(\\d+)\"");
    /**
     * 削除された・存在しない動画のページにある目印。
     *
     * <p>存在しない動画 ID の動画ページは HTTP 200 で返り、この目印を持ち、{@code isUpcoming}・{@code isLiveContent} を持たない
     * （実際のページで確かめた）。{@code UNPLAYABLE}（メンバー限定・地域制限）は動画が残っているので含めない。
     * 非公開の {@code LOGIN_REQUIRED} は、ボット確認の画面と同じ値なので区別できず、判定できない（null）のままにする。
     */
    private static final String PLAYABILITY_ERROR = "\"playabilityStatus\":{\"status\":\"ERROR\"";

    private YouTubeVideoKindParser() {}

    /** 判定結果。{@code scheduledStartTime} は配信予定のときだけ入る。 */
    public record Result(String kind, Instant scheduledStartTime) {}

    /**
     * 動画ページの HTML から、動画の種類と開始予定時刻を読み取る。
     *
     * <p>目印が無いときに投稿動画として返さないのは、判定できなかった動画を
     * 投稿済みの段に誤って並べないため。
     *
     * @param html 動画ページの HTML
     * @return 判定結果。{@code html} が null のとき、または目印が一つも無いとき
     *         （ログイン画面・同意画面・ボット確認・非公開・構造変化など）は null。削除された・存在しない動画は {@link OnlineVideo#KIND_MISSING}
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
        if (html.contains(PLAYABILITY_ERROR)) return new Result(OnlineVideo.KIND_MISSING, null);
        return null;
    }
}
