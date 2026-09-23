package com.example.monitor.service;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.NotificationOutcome;
import com.example.monitor.notification.DiscordNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 配信開始の通知を送り出し、その結果を呼び出し側に返す。
 *
 * <p>実際の送信は {@link DiscordNotifier} が行う。このクラスの役割は、
 * 送信時に発生した例外を捕まえて {@link NotificationOutcome} に変換することにある。
 * 監視ループが「失敗しても止まらず、次のサイクルで再送信する」という方針を取るため、
 * 例外をそのまま上位に伝播させたくないという意図。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDispatcher {

    private final DiscordNotifier discordNotifier;

    /**
     * 配信が始まったことを通知する。
     *
     * <p>送信に失敗しても例外は投げない。呼び出し側は戻り値を見て、
     * 成功した場合にだけ「通知済み」として記録することで、
     * 失敗時は次回の監視サイクルが自動的に再送信の役割を果たす。
     *
     * @param liveStream 通知対象の配信情報
     * @return 送信結果。失敗時は理由を含む
     */
    public NotificationOutcome notifyLiveStreamStarted(LiveStreamDetails liveStream) {
        try {
            discordNotifier.sendLiveStartNotification(liveStream);
            log.info("配信開始を通知しました: channel={}, title={}",
                    liveStream.getChannelTitle(), liveStream.getTitle());
            return NotificationOutcome.success();
        } catch (Exception e) {
            log.error("Discord への通知送信に失敗しました: title={}", liveStream.getTitle(), e);
            return NotificationOutcome.failure(e.getMessage());
        }
    }
}
