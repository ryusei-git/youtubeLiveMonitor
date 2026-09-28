package com.example.monitor.dto;

import java.util.List;

/**
 * 現在有効になっている設定値。画面で確認するためのもの。
 *
 * <p>設定は {@code .env}（環境変数）→ {@code application.yml} の既定値、の順で決まるため、
 * ファイルを見ただけでは「今どの値で動いているか」が分からない。再起動を挟むと
 * なおさら食い違いやすいので、アプリが実際に読み込んだ値をそのまま見せる。
 *
 * <p>加えて、{@code .env} に保存済みの値と「再起動待ち」の項目名も返す。画面が適用中の値だけを見て
 * フォームを埋めると、再起動待ちの変更が見えない。そのまま別の項目を保存すると、黙って旧値へ戻してしまうため。
 * OS の環境変数で同じキーを与えて起動した場合は、{@code .env} と食い違ったまま再起動待ちに見える
 * （このサービスは {@code .env} だけで設定する運用のため、そのケースは扱わない）。
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
 * @param savedIntervalSeconds     {@code .env} に保存済みの監視間隔。キーが無いか数値でなければ {@code null}
 * @param savedRecordingDirectory  {@code .env} に保存済みの保存先。キーが無いか空なら {@code null}
 * @param savedRecordingMaxHeight  {@code .env} に保存済みの画質上限。キーが無いか数値でなければ {@code null}
 * @param pendingRestartKeys       {@code .env} の値が起動時に読んだ値と違う項目の {@code .env} のキー名（再起動待ち）。
 *                                 秘密情報の項目も、値は載せずキー名だけを入れる
 */
public record SettingsResponse(
        int intervalSeconds,
        String recordingDirectory,
        int recordingMaxHeight,
        boolean youTubeApiKeyConfigured,
        boolean discordWebhookConfigured,
        boolean twitchConfigured,
        Integer savedIntervalSeconds,
        String savedRecordingDirectory,
        Integer savedRecordingMaxHeight,
        List<String> pendingRestartKeys
) {}
