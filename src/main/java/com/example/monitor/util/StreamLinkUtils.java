package com.example.monitor.util;

import com.example.monitor.platform.Platform;

/** 保存されていないURLを推測して無関係なページへ誘導しないための共通処理。 */
public final class StreamLinkUtils {
    private StreamLinkUtils() {}

    /** 動画IDだけで復元できるYouTube以外では、保存済みURLだけを使う。 */
    public static String videoUrl(Platform platform, String videoId, String sourceUrl) {
        if (sourceUrl != null && !sourceUrl.isBlank()) return sourceUrl;
        return platform == Platform.YOUTUBE && videoId != null
                ? "https://www.youtube.com/watch?v=" + videoId : null;
    }

    /** Twitchの不変IDはログイン名とは異なるため、チャンネルURLを推測しない。 */
    public static String channelUrl(Platform platform, String channelId) {
        return platform == Platform.YOUTUBE && channelId != null
                ? "https://www.youtube.com/channel/" + channelId : null;
    }

    /** TwitchのURLはユーザーIDでは開けないため、保存済みのログイン名があるときだけ作る。 */
    public static String channelUrl(Platform platform, String channelId, String login) {
        if (platform != Platform.TWITCH) return channelUrl(platform, channelId);
        return login != null && !login.isBlank() ? "https://www.twitch.tv/" + login : null;
    }
}
