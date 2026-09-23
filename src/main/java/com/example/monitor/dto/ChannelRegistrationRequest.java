package com.example.monitor.dto;

import com.example.monitor.platform.Platform;
import jakarta.validation.constraints.NotBlank;

/**
 * チャンネルを監視対象に登録するときのリクエスト。
 *
 * @param platform         どのプラットフォームのチャンネルか。省略時は {@link Platform#YOUTUBE}
 *                         （プラットフォーム選択を導入する前の呼び出しをそのまま動かすため）
 * @param youtubeChannelId チャンネルの指定。YouTube ならチャンネル ID（{@code UC...}）・
 *                         ハンドル（{@code @foo}）・URL、Twitch ならログイン名・URL。
 *                         形式は登録時に自動で吸収される
 * @param channelName      画面やログで表示するチャンネル名
 * @param recordEnabled    配信を検知した際に自動録画するか（省略時は {@code false}）
 * @param recordTitleKeywords 通知・録画の対象を絞り込むタイトルキーワード（カンマ区切り）。
 *                            省略・空なら絞り込みなし
 */
public record ChannelRegistrationRequest(
        Platform platform,
        @NotBlank String youtubeChannelId,
        @NotBlank String channelName,
        boolean recordEnabled,
        String recordTitleKeywords
) {

    /**
     * 指定されたプラットフォームを返す。未指定なら YouTube とみなす。
     *
     * <p>項目を増やす前から動いている呼び出し（CLI スクリプトや手元の {@code curl} など）を
     * 壊さないために、省略を許して既定値で補う。
     *
     * @return プラットフォーム
     */
    public Platform platformOrDefault() {
        return platform == null ? Platform.YOUTUBE : platform;
    }
}
