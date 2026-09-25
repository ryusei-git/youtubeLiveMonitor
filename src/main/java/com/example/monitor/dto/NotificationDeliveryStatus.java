package com.example.monitor.dto;

import java.time.LocalDateTime;

/**
 * 利用者への通知が届いているかの状況。通知の設定画面で、Webhook が壊れていることに利用者が気付けるようにするために使う。
 *
 * <p>Webhook を登録し直すと前の失敗は消える（{@code UserNotificationRepository#clearFailures}）ため、
 * {@code lastFailedAt} は今の Webhook での失敗だけを指す。
 *
 * @param lastDeliveredAt 最後に届けた時刻。一度も届けていなければ {@code null}
 * @param lastFailedAt    今の Webhook で最後に送れなかった時刻。無ければ {@code null}
 */
public record NotificationDeliveryStatus(LocalDateTime lastDeliveredAt, LocalDateTime lastFailedAt) {

    /**
     * 最近の通知が届いていないとみなすかを返す。最後の失敗が最後の成功より新しいとき。
     *
     * <p>失敗の後に 1 度でも届いていれば、失敗は一時的なもの（Discord の一時的な不調など）として警告しない。
     * 判定をサーバーに置くのは、画面ごとに条件がずれないようにするため。
     *
     * @return 警告すべきなら {@code true}
     */
    public boolean failing() {
        return lastFailedAt != null && (lastDeliveredAt == null || lastFailedAt.isAfter(lastDeliveredAt));
    }
}
