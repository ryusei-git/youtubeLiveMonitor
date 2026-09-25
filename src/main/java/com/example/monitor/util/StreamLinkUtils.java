package com.example.monitor.util;

import com.example.monitor.platform.Platform;

/**
 * チャンネル・動画のリンク先の URL を組み立てる。
 *
 * <p>保存されていないURLを推測して無関係なページへ誘導しないための共通処理。
 */
public final class StreamLinkUtils {
    private StreamLinkUtils() {}

    /**
     * 動画のリンク先の URL を返す。
     *
     * <p>動画IDだけで復元できるYouTube以外では、保存済みURLだけを使う。
     *
     * @param platform 動画の配信元
     * @param videoId 動画ID。YouTube で保存済みURLが無いときの組み立てに使う
     * @param sourceUrl 保存済みのURL。空でなければそのまま返す
     * @return 動画の URL。保存済みURLが空で、YouTube 以外か {@code videoId} が null なら null
     */
    public static String videoUrl(Platform platform, String videoId, String sourceUrl) {
        if (sourceUrl != null && !sourceUrl.isBlank()) return sourceUrl;
        return platform == Platform.YOUTUBE && videoId != null
                ? YouTubeWatchUrl.of(videoId) : null;
    }

    /**
     * チャンネルのリンク先の URL を返す。
     *
     * <p>Twitchの不変IDはログイン名とは異なるため、チャンネルURLを推測しない。
     *
     * @param platform チャンネルの配信元
     * @param channelId チャンネルID
     * @return YouTube のチャンネルの URL。YouTube 以外か {@code channelId} が null なら null
     */
    public static String channelUrl(Platform platform, String channelId) {
        return platform == Platform.YOUTUBE && channelId != null
                ? "https://www.youtube.com/channel/" + channelId : null;
    }

    /**
     * ログイン名も使って、チャンネルのリンク先の URL を返す。
     *
     * <p>TwitchのURLはユーザーIDでは開けないため、保存済みのログイン名があるときだけ作る。
     *
     * @param platform チャンネルの配信元
     * @param channelId チャンネルID。Twitch 以外のときに使う
     * @param login 保存済みのログイン名。Twitch のときだけ使う
     * @return チャンネルの URL。Twitch で {@code login} が空のとき、または Twitch 以外で
     *         {@link #channelUrl(Platform, String)} が null を返すときは null
     */
    public static String channelUrl(Platform platform, String channelId, String login) {
        if (platform != Platform.TWITCH) return channelUrl(platform, channelId);
        return login != null && !login.isBlank() ? "https://www.twitch.tv/" + login : null;
    }
}
