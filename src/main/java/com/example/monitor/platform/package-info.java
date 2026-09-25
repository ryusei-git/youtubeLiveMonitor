/**
 * 配信プラットフォーム（YouTube・Twitch）ごとの差（識別子・配信の検知・視聴 URL）を吸収する。
 *
 * <p>チャンネルの識別子は、必ず {@link com.example.monitor.platform.StreamPlatform#normalizeChannelInput(String)}
 * を通した不変の ID を使う（YouTube のハンドルや Twitch のログイン名は変わりうるので、識別子には使わない）。
 */
package com.example.monitor.platform;
