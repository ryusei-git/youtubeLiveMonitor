package com.example.monitor.dto;

import java.time.LocalDateTime;

/**
 * 利用者の Discord 通知の設定の状態。
 *
 * <p><b>Webhook の URL は含めない。</b>URL を知っていれば誰でもその Discord のチャンネルへ書き込めるため、
 * 画面には登録済みかどうかだけを出す（管理者の設定画面の {@link SettingsResponse} と同じ作法）。
 *
 * <p>届けた・送れなかった時刻を返すのは、Discord 側で Webhook が消されても利用者には通知が来なくなるだけで、
 * 壊れていることに気付く手段が無いため。{@code failing} をサーバーで判定して返すのは、
 * 条件を {@link NotificationDeliveryStatus#failing()} の 1 か所に置き、画面で判定し直さないため。
 *
 * @param configured      Webhook を登録しているか
 * @param lastDeliveredAt 最後に届けた時刻。一度も届けていなければ {@code null}
 * @param lastFailedAt    今の Webhook で最後に送れなかった時刻。無ければ {@code null}
 * @param failing         最近の通知が届いていないとみなすか
 */
public record NotificationSettingsResponse(boolean configured, LocalDateTime lastDeliveredAt,
                                           LocalDateTime lastFailedAt, boolean failing) {
}
