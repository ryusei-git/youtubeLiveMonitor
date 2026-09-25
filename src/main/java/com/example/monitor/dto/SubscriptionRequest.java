package com.example.monitor.dto;

import com.example.monitor.platform.Platform;
import jakarta.validation.constraints.NotBlank;

/**
 * 利用者がチャンネルを購読するときのリクエスト。
 *
 * <p>{@code recordEnabled} は<b>自分の購読の</b>録画の希望で、チャンネル単位の設定ではない。
 * 追加するときに選べるようにしているのは、既定の OFF のまま購読した利用者が
 * 「購読すれば録画される」と思い込み、アーカイブが空でも理由に気付けなかったため。
 *
 * @param platform     どのプラットフォームか。省略時は {@link Platform#YOUTUBE}
 * @param channelInput チャンネルの指定。チャンネル ID・ハンドル・ログイン名・URL のいずれでもよい
 *                     （形式の違いは {@code StreamPlatform.normalizeChannelInput()} が吸収する）
 * @param channelName  画面に表示する名前。未入力なら入力された識別子をそのまま使う
 * @param recordEnabled 配信を自動で録画するか。省略時は録画しない
 */
public record SubscriptionRequest(
        Platform platform,
        @NotBlank String channelInput,
        String channelName,
        Boolean recordEnabled
) {

    /**
     * 指定されたプラットフォームを返す。未指定なら YouTube とみなす。
     *
     * @return プラットフォーム
     */
    public Platform platformOrDefault() {
        return platform == null ? Platform.YOUTUBE : platform;
    }

    /**
     * 録画を希望するかを返す。
     *
     * <p>省略時は今までどおり録画しない。ディスクを使う操作は利用者が選んだときだけにするため。
     *
     * @return 録画を希望するなら {@code true}
     */
    public boolean recordEnabledOrDefault() {
        return Boolean.TRUE.equals(recordEnabled);
    }
}
