package com.example.monitor.util;

import java.util.regex.Pattern;

/**
 * YouTube の動画 ID から視聴ページ・サムネイルの URL を組み立て、動画 ID の形を確かめる。
 *
 * <p>組み立て自体は 1 行だが、<b>配信を検知した時</b>（録画で {@code yt-dlp} に渡す URL）、
 * <b>通知本文を作る時</b>（Discord の埋め込みリンク）、<b>投稿動画を取り込む時</b>
 * （フィードと uploads 一覧）など複数の場所で必要になる。
 * それぞれのクラスに同じ文字列を書くと、片方だけ直して食い違う余地が生まれるため
 * ここに集約している。
 */
public final class YouTubeWatchUrl {

    /** 視聴ページの URL の形。 */
    private static final String TEMPLATE = "https://www.youtube.com/watch?v=%s";

    /** サムネイル画像の URL の形。 */
    private static final String THUMBNAIL_TEMPLATE = "https://i.ytimg.com/vi/%s/hqdefault.jpg";

    /** 動画 ID の形（英数字と {@code _}・{@code -} の 11 文字）。 */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** ユーティリティクラスのためインスタンス化させない。 */
    private YouTubeWatchUrl() {
    }

    /**
     * 動画 ID から視聴ページの URL を組み立てる。
     *
     * @param videoId 動画 ID（{@code watch?v=} の後ろに来る 11 文字）
     * @return 視聴ページの URL
     */
    public static String of(String videoId) {
        return TEMPLATE.formatted(videoId);
    }

    /**
     * 動画 ID からサムネイル画像の URL を組み立てる。
     *
     * <p>API を叩かずに一覧へ画像を出せるよう、動画 ID だけで決まる URL を使う。
     * 解像度は {@code hqdefault}（どの動画にも必ずある大きさ）に固定している。
     *
     * @param videoId 動画 ID
     * @return サムネイル画像の URL
     */
    public static String thumbnailOf(String videoId) {
        return THUMBNAIL_TEMPLATE.formatted(videoId);
    }

    /**
     * 値が動画 ID の形をしているかを確かめる。
     *
     * <p>フィードや API の応答に動画以外の ID（再生リストなど）が混ざったときに、
     * 保存しないため。
     *
     * @param value 確かめる値（{@code null} 可）
     * @return 動画 ID の形なら {@code true}
     */
    public static boolean isVideoId(String value) {
        return value != null && VIDEO_ID.matcher(value).matches();
    }
}
