package com.example.monitor.platform.twitch;

/**
 * Twitch Helix API の {@code /helix/streams} が返す配信 1 件分。
 *
 * <p>この応答が返る＝その時点で配信中、という意味を持つ。オフラインのチャンネルは
 * 応答の {@code data} に一切現れない（「配信していない」を表す専用の項目は無い）。
 *
 * @param id           配信 ID。配信ごとに変わるため、YouTube の動画 ID と同じ役割を果たす
 * @param userId       配信者のユーザー ID（数値文字列・不変）
 * @param userLogin    配信者のログイン名（URL に現れる名前・変更されうる）
 * @param userName     配信者の表示名
 * @param title        配信タイトル
 * @param gameName     配信中のカテゴリ・ゲーム名。設定されていなければ空文字
 * @param viewerCount  同時視聴者数
 * @param thumbnailUrl サムネイル URL。寸法が {@code {width}}/{@code {height}} の
 *                     プレースホルダーになっているため、そのままでは画像として使えない
 *                     （{@link #resolvedThumbnailUrl()} を使うこと）
 * @param startedAt    配信開始時刻。ISO 8601（{@code 2026-09-19T22:04:37Z}）の文字列
 */
public record TwitchStream(
        String id,
        String userId,
        String userLogin,
        String userName,
        String title,
        String gameName,
        int viewerCount,
        String thumbnailUrl,
        String startedAt
) {

    /** サムネイル URL に埋め込む幅。Discord の埋め込みで潰れない程度の大きさにする。 */
    private static final String THUMBNAIL_WIDTH = "640";

    /** サムネイル URL に埋め込む高さ。Twitch のサムネイルは 16:9。 */
    private static final String THUMBNAIL_HEIGHT = "360";

    /** 視聴ページの URL の形。Twitch はチャンネルのページがそのまま配信の視聴ページになる。 */
    private static final String WATCH_URL_TEMPLATE = "https://www.twitch.tv/%s";

    /**
     * この配信の視聴ページ URL を組み立てて返す。録画時に {@code yt-dlp} へ渡す URL でもある。
     *
     * <p><b>配信 ID からは作れないので、ログイン名を使う。</b>
     * {@code twitch.tv/videos/{配信ID}} という形も一見それらしく見えるうえ、
     * Twitch は SPA のため<b>存在しないページでも HTTP 200 を返す</b>が、
     * 配信 ID と VOD の ID は別物なので中身は存在しない（実機で確認済み）。
     * ステータスコードだけを見て動作確認すると、この誤りに気づけない。
     *
     * @return 視聴ページの URL
     */
    public String watchUrl() {
        return WATCH_URL_TEMPLATE.formatted(userLogin);
    }

    /**
     * サムネイル URL のプレースホルダーを実際の寸法に置き換えて返す。
     *
     * <p>Twitch はサムネイル URL を
     * {@code .../preview-{width}x{height}.jpg} の形で返す。置き換えないまま
     * 画像として読み込ませても表示されないため、通知を組み立てる前に必ずここを通す。
     *
     * @return そのまま画像として使える URL。元が未設定なら {@code null}
     */
    public String resolvedThumbnailUrl() {
        if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
            return null;
        }
        return thumbnailUrl
                .replace("{width}", THUMBNAIL_WIDTH)
                .replace("{height}", THUMBNAIL_HEIGHT);
    }
}
