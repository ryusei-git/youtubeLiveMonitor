package com.example.monitor.controller;

import com.example.monitor.dto.NotificationDeliveryStatus;
import com.example.monitor.dto.NotificationOutcome;
import com.example.monitor.dto.NotificationSettingsRequest;
import com.example.monitor.dto.NotificationSettingsResponse;
import com.example.monitor.service.UserNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ログイン中の利用者自身の Discord 通知の設定を扱う API。
 *
 * <p>{@code /api/my/} の下に置き、対象は常にログイン中の本人にする（{@link MyChannelController} と同じ理由。
 * 利用者をリクエストから指定できる形にすると、検証漏れがそのまま他人の設定の書き換えになる）。
 *
 * <p><b>Webhook の URL はどの応答にも載せない。</b>返すのは登録済みかどうかと届いた・送れなかった時刻（{@link NotificationSettingsResponse}）だけ。
 *
 * <p>依存する {@link UserNotificationService} はプロファイルを問わず作られるため、
 * {@code @Profile("!cli")} は付けていない。
 */
@RestController
@RequestMapping("/api/my/notification-settings")
@RequiredArgsConstructor
public class MyNotificationSettingsController {

    private final UserNotificationService userNotificationService;

    /**
     * Webhook を登録しているかと、通知が届いているかを返す。
     *
     * @return 設定の状態
     */
    @GetMapping
    public NotificationSettingsResponse getSettings() {
        return currentSettings();
    }

    /**
     * Webhook を登録する。登録済みなら置き換える。
     *
     * @param request 登録する Webhook の URL
     * @return 設定の状態。Discord の Webhook の URL でなければ 400
     */
    @PutMapping
    public NotificationSettingsResponse registerWebhook(@RequestBody NotificationSettingsRequest request) {
        userNotificationService.registerWebhook(request.webhookUrl());
        return currentSettings();
    }

    /**
     * Webhook を解除する。登録していなくても成功として扱う。
     *
     * @return 設定の状態
     */
    @DeleteMapping
    public NotificationSettingsResponse unregisterWebhook() {
        userNotificationService.unregisterWebhook();
        return currentSettings();
    }

    /**
     * 今の設定の状態を組み立てる。登録・解除の応答も読み込みと同じ内容にするのは、登録し直すと過去の失敗が消える
     * （{@code UserNotificationRepository#clearFailures}）ため、画面が警告を消すには登録後の状態を知る必要があるから。
     *
     * <p>Webhook を登録していなければ {@code failing} は常に {@code false} にする。届けようがないので
     * 「作り直して保存し、テスト送信で確かめて」と促す警告は出せない。解除では前の失敗を消すが、解除と同時に
     * 巡回が古い URL へ送って失敗を記録することもあるため、消すだけに頼らずここでも判定する。
     *
     * @return 設定の状態
     */
    private NotificationSettingsResponse currentSettings() {
        NotificationDeliveryStatus status = userNotificationService.getDeliveryStatus();
        boolean configured = userNotificationService.isWebhookConfigured();
        return new NotificationSettingsResponse(configured,
                status.lastDeliveredAt(), status.lastFailedAt(), configured && status.failing());
    }

    /**
     * 登録した Webhook へテストの通知を 1 件送る。
     *
     * <p>Discord に届かなかったときは 502 と理由を返す。こちらの誤りではなく送り先が受け付けなかった
     * （Webhook が消されている等）ことを区別して示すため。本文の形は他の失敗と同じ {@code {"error": 理由}}。
     *
     * @return 送れたら 204。Webhook を登録していなければ 400、Discord に届かなければ 502
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> sendTestNotification() {
        NotificationOutcome outcome = userNotificationService.sendTestNotification();
        if (outcome.successful()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", outcome.errorMessage()));
    }
}
