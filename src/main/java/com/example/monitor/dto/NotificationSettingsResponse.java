package com.example.monitor.dto;

/**
 * 利用者の Discord 通知の設定の状態。
 *
 * <p><b>Webhook の URL は含めない。</b>URL を知っていれば誰でもその Discord のチャンネルへ書き込めるため、
 * 画面には登録済みかどうかだけを出す（管理者の設定画面の {@link SettingsResponse} と同じ作法）。
 *
 * @param configured Webhook を登録しているか
 */
public record NotificationSettingsResponse(boolean configured) {
}
