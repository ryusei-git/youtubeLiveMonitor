package com.example.monitor.dto;

/**
 * 現在有効になっている設定値。画面で確認するためのもの。
 *
 * <p>設定は {@code .env}（環境変数）→ {@code application.yml} の既定値、の順で決まるため、
 * ファイルを見ただけでは「今どの値で動いているか」が分からない。再起動を挟むと
 * なおさら食い違いやすいので、アプリが実際に読み込んだ値をそのまま見せる。
 *
 * <p><b>APIキーや Webhook URL のような秘密情報は含めない。</b>画面には
 * 「設定されているかどうか」だけを出し、値そのものは返さない。
 *
 * @param intervalSeconds       監視の実行間隔（秒）
 * @param recordingDirectory    録画ファイルの保存先ディレクトリ
 * @param recordingMaxHeight    録画する画質の上限（ピクセル）。{@code 0} は上限なし
 * @param youTubeApiKeyConfigured  YouTube API キーが設定されているか
 * @param discordWebhookConfigured Discord Webhook URL が設定されているか
 * @param twitchConfigured         Twitch の Client ID と Client Secret が両方設定されているか。
 *                                 片方だけでは認証できないため、まとめて 1 つの状態として扱う
 */
public record SettingsResponse(
        int intervalSeconds,
        String recordingDirectory,
        int recordingMaxHeight,
        boolean youTubeApiKeyConfigured,
        boolean discordWebhookConfigured,
        boolean twitchConfigured
) {}
