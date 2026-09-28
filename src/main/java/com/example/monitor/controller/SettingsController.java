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

import java.util.LinkedHashMap;
import java.util.Map;
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
     * 現在の設定値を返す。
     *
     * @return 設定値（秘密情報は設定有無のみ）
     */
    @GetMapping
    public SettingsResponse getSettings() {
        return new SettingsResponse(
                monitorProperties.youtube().intervalSeconds(),
                monitorProperties.recording().directory(),
                monitorProperties.recording().maxHeight(),
                isConfigured(monitorProperties.youtube().apiKey()),
                isConfigured(monitorProperties.discord().webhookUrl()),
                monitorProperties.twitch().isConfigured());
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
}
