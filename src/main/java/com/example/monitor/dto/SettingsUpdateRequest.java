package com.example.monitor.dto;

/**
 * ダッシュボードの「現在の設定」から送られる変更内容。
 *
 * <p>各項目は「指定が無ければ変更しない」という扱いにしている。特に秘密情報
 * （APIキー・Webhook URL）は現在の値を画面に一切返していない（{@link SettingsResponse}参照）ため、
 * 空欄のまま保存しても既存の値を消してしまわないようにするための設計。
 *
 * <p>ここで受けた値は {@code .env} に書き込むだけで、実行中のアプリには反映されない
 * （{@link com.example.monitor.config.MonitorProperties} はアプリ起動時に一度だけ読み込まれる
 * ため）。反映には保存後に {@code bin/service.sh restart} が必要
 * （{@link com.example.monitor.service.EnvironmentSettingsService}のJavaDoc参照）。
 *
 * @param youtubeApiKey      YouTube APIキー。{@code null}または空文字なら変更しない
 * @param discordWebhookUrl  Discord Webhook URL。{@code null}または空文字なら変更しない
 * @param twitchClientId     Twitch の Client ID。{@code null}または空文字なら変更しない
 * @param twitchClientSecret Twitch の Client Secret。{@code null}または空文字なら変更しない
 * @param intervalSeconds    監視の実行間隔（秒）。{@code null}なら変更しない
 * @param recordingDirectory 録画ファイルの保存先ディレクトリ。{@code null}または空文字なら変更しない
 * @param recordingMaxHeight 録画する画質の上限（ピクセル）。{@code null}なら変更しない
 */
public record SettingsUpdateRequest(
        String youtubeApiKey,
        String discordWebhookUrl,
        String twitchClientId,
        String twitchClientSecret,
        Integer intervalSeconds,
        String recordingDirectory,
        Integer recordingMaxHeight
) {}
