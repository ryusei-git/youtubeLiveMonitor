package com.example.monitor.util;

/**
 * YouTube の動画 ID から視聴ページの URL を組み立てる。
 *
 * <p>組み立て自体は 1 行だが、<b>配信を検知した時</b>（録画で {@code yt-dlp} に渡す URL）と
 * <b>通知本文を作る時</b>（Discord の埋め込みリンク）の 2 か所で必要になる。
 * それぞれのクラスに同じ文字列を書くと、片方だけ直して食い違う余地が生まれるため
 * ここに集約している。
 */
public final class YouTubeWatchUrl {

    /** 視聴ページの URL の形。 */
    private static final String TEMPLATE = "https://www.youtube.com/watch?v=%s";

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
}
