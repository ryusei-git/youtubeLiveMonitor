package com.example.monitor.dto;

/**
 * 利用者の Discord 通知の設定（Webhook の登録・変更）の要求。
 *
 * @param webhookUrl 登録する Discord の Webhook の URL
 */
public record NotificationSettingsRequest(String webhookUrl) {

    /**
     * URL を伏せた文字列にする。
     *
     * <p>record の既定の {@code toString} は全項目を出すため、この要求がどこかでログに出る
     * （Spring の DEBUG ログは読み取った本文をこの形で出す）と、秘密の URL がそのまま残る。
     *
     * @return URL を伏せた文字列
     */
    @Override
    public String toString() {
        return "NotificationSettingsRequest[webhookUrl=***]";
    }
}
