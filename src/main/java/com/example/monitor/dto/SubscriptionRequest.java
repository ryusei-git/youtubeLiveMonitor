package com.example.monitor.dto;

import com.example.monitor.platform.Platform;
import jakarta.validation.constraints.NotBlank;

/**
 * 利用者がチャンネルを購読するときのリクエスト。
 *
 * <p>録画に関する項目を受け取らないのは意図的。録画はチャンネル単位の設定で、
 * 利用者が変えると他の利用者にも影響するため（{@link SubscribedChannelResponse} 参照）。
 *
 * @param platform     どのプラットフォームか。省略時は {@link Platform#YOUTUBE}
 * @param channelInput チャンネルの指定。チャンネル ID・ハンドル・ログイン名・URL のいずれでもよい
 *                     （形式の違いは {@code StreamPlatform.normalizeChannelInput()} が吸収する）
 * @param channelName  画面に表示する名前。未入力なら入力された識別子をそのまま使う
 */
public record SubscriptionRequest(
        Platform platform,
        @NotBlank String channelInput,
        String channelName
) {

    /**
     * 指定されたプラットフォームを返す。未指定なら YouTube とみなす。
     *
     * @return プラットフォーム
     */
    public Platform platformOrDefault() {
        return platform == null ? Platform.YOUTUBE : platform;
    }
}
