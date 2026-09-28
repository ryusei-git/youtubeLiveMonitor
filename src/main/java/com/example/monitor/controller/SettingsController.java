package com.example.monitor.controller;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.DirectoryPickResponse;
import com.example.monitor.dto.SettingsResponse;
import com.example.monitor.dto.SettingsUpdateRequest;
import com.example.monitor.service.EnvironmentSettingsService;
import com.example.monitor.service.NativeDirectoryPickerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 設定値を参照・変更する REST API。
 *
 * <p>秘密情報（API キー・Webhook URL）は「設定されているか」だけを返し、
 * 値そのものは決して返さない（{@link SettingsResponse} の JavaDoc 参照）。
 *
 * <p>変更は {@code .env} への書き込みのみで、実行中のアプリには反映されない。
 * 反映には保存後に {@code bin/service.sh restart} が必要
 * （{@link EnvironmentSettingsService} の JavaDoc 参照）。
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    /** {@code .env} に書き込む際のキー名。{@code application.yml} の参照名と対応させている。 */
    private static final String KEY_YOUTUBE_API_KEY = "YOUTUBE_API_KEY";
    private static final String KEY_DISCORD_WEBHOOK_URL = "DISCORD_WEBHOOK_URL";
    private static final String KEY_TWITCH_CLIENT_ID = "TWITCH_CLIENT_ID";
    private static final String KEY_TWITCH_CLIENT_SECRET = "TWITCH_CLIENT_SECRET";
    private static final String KEY_INTERVAL_SECONDS = "MONITOR_INTERVAL_SECONDS";
    private static final String KEY_RECORDING_DIRECTORY = "MONITOR_RECORDING_DIRECTORY";
    private static final String KEY_RECORDING_MAX_HEIGHT = "MONITOR_RECORDING_MAX_HEIGHT";

    private final MonitorProperties monitorProperties;
    private final EnvironmentSettingsService environmentSettingsService;
    private final NativeDirectoryPickerService nativeDirectoryPickerService;

    /**
     * 適用中の設定値と、{@code .env} に保存済みで再起動を待っている値を返す。
     *
     * <p>保存済みの値も返すのは、画面が適用中の値だけでフォームを埋めると、別の項目を保存したときに
     * 再起動待ちの変更を黙って旧値へ戻してしまうため（{@link SettingsResponse} 参照）。
     *
     * @return 設定値（秘密情報は設定有無と再起動待ちのキー名のみ）
     */
    @GetMapping
    public SettingsResponse getSettings() {
        Map<String, String> saved = environmentSettingsService.readEnvValues();
        int intervalSeconds = monitorProperties.youtube().intervalSeconds();
        String recordingDirectory = monitorProperties.recording().directory();
        int recordingMaxHeight = monitorProperties.recording().maxHeight();
        String savedRecordingDirectory = saved.get(KEY_RECORDING_DIRECTORY);

        List<String> pendingRestartKeys = new ArrayList<>();
        addIfPending(pendingRestartKeys, saved, KEY_INTERVAL_SECONDS, String.valueOf(intervalSeconds));
        addIfPending(pendingRestartKeys, saved, KEY_RECORDING_DIRECTORY, recordingDirectory);
        addIfPending(pendingRestartKeys, saved, KEY_RECORDING_MAX_HEIGHT, String.valueOf(recordingMaxHeight));
        addIfPending(pendingRestartKeys, saved, KEY_YOUTUBE_API_KEY, monitorProperties.youtube().apiKey());
        addIfPending(pendingRestartKeys, saved, KEY_DISCORD_WEBHOOK_URL, monitorProperties.discord().webhookUrl());
        addIfPending(pendingRestartKeys, saved, KEY_TWITCH_CLIENT_ID, monitorProperties.twitch().clientId());
        addIfPending(pendingRestartKeys, saved, KEY_TWITCH_CLIENT_SECRET, monitorProperties.twitch().clientSecret());

        return new SettingsResponse(
                intervalSeconds,
                recordingDirectory,
                recordingMaxHeight,
                isConfigured(monitorProperties.youtube().apiKey()),
                isConfigured(monitorProperties.discord().webhookUrl()),
                monitorProperties.twitch().isConfigured(),
                parseIntOrNull(saved.get(KEY_INTERVAL_SECONDS)),
                savedRecordingDirectory == null || savedRecordingDirectory.isBlank() ? null : savedRecordingDirectory,
                parseIntOrNull(saved.get(KEY_RECORDING_MAX_HEIGHT)),
                List.copyOf(pendingRestartKeys));
    }

    /**
     * 設定値を {@code .env} に保存する。
     *
     * <p>指定が無い（{@code null}または空文字の）項目は変更しない。
     * 保存しても実行中のアプリにはすぐ反映されないため、反映には
     * {@code bin/service.sh restart} が必要（{@link EnvironmentSettingsService}参照）。
     *
     * <p>保存できない値は 400、{@code .env} を読めない・書けないときは 500 で、どちらも {@code .env} は変わらない。
     *
     * @param request 変更したい項目
     * @return 本文なしの HTTP 204
     */
    @PutMapping
    public ResponseEntity<Void> updateSettings(@Valid @RequestBody SettingsUpdateRequest request) {
        Map<String, String> updates = new LinkedHashMap<>();
        putIfPresent(updates, KEY_YOUTUBE_API_KEY, request.youtubeApiKey());
        putIfPresent(updates, KEY_DISCORD_WEBHOOK_URL, request.discordWebhookUrl());
        putIfPresent(updates, KEY_TWITCH_CLIENT_ID, request.twitchClientId());
        putIfPresent(updates, KEY_TWITCH_CLIENT_SECRET, request.twitchClientSecret());
        putIfPresent(updates, KEY_RECORDING_DIRECTORY, request.recordingDirectory());
        if (request.intervalSeconds() != null) {
            updates.put(KEY_INTERVAL_SECONDS, String.valueOf(request.intervalSeconds()));
        }
        if (request.recordingMaxHeight() != null) {
            updates.put(KEY_RECORDING_MAX_HEIGHT, String.valueOf(request.recordingMaxHeight()));
        }

        environmentSettingsService.updateEnvFile(updates);
        return ResponseEntity.noContent().build();
    }

    /**
     * 録画の保存先を、OS のフォルダ選択ダイアログで選ばせる。
     *
     * <p>ブラウザは選んだフォルダの絶対パスを教えてくれないため、サーバー側のプロセスから
     * 直接 OS のダイアログを起動している（{@link NativeDirectoryPickerService}参照）。
     * サーバーとブラウザが同じマシン上にある運用を前提とした機能で、リモート利用時は
     * ダイアログがサーバー側の画面に表示されるため実質使えない。
     *
     * @param initialDirectory ダイアログを開く初期ディレクトリ。省略すると OS の既定
     * @return 選ばれたパス。キャンセルされた場合は本文なしの HTTP 204
     */
    @PostMapping("/directories/pick")
    public ResponseEntity<DirectoryPickResponse> pickDirectory(@RequestParam(required = false) String initialDirectory) {
        Optional<String> selected = nativeDirectoryPickerService.pickDirectory(initialDirectory);
        return selected
                .map(path -> ResponseEntity.ok(new DirectoryPickResponse(path)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 値が入力されている場合だけ更新内容に加える。
     *
     * @param updates 更新内容を集めるマップ
     * @param key     {@code .env} のキー名
     * @param value   利用者からの入力値。{@code null}または空文字なら何もしない。前後の空白は落とす
     */
    private void putIfPresent(Map<String, String> updates, String key, String value) {
        if (value != null && !value.isBlank()) {
            updates.put(key, value.strip());
        }
    }

    /**
     * 設定値が入力されているかを判定する。
     *
     * @param value 判定対象の設定値
     * @return 空でなければ {@code true}
     */
    private boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * {@code .env} に書かれた値が起動時に読んだ値と違えば、再起動待ちとしてキー名を加える。
     *
     * <p>値ではなくキー名だけを返すのは、秘密情報（API キー等）の値を応答に載せないため。
     * {@code .env} にキーが無い項目は、既定値で動いているだけなので再起動待ちではない。
     *
     * @param pending キー名を集めるリスト
     * @param saved   {@code .env} に書かれたキーと値
     * @param key     {@code .env} のキー名
     * @param applied 起動時に読んだ値。{@code null} は空文字として比べる
     */
    private void addIfPending(List<String> pending, Map<String, String> saved, String key, String applied) {
        String savedValue = saved.get(key);
        if (savedValue != null && !savedValue.equals(Objects.requireNonNullElse(applied, ""))) {
            pending.add(key);
        }
    }

    /**
     * 数値として読める場合だけ整数にする。
     *
     * @param value {@code .env} に書かれた値
     * @return 整数。{@code null} か数値でなければ {@code null}
     */
    private Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
