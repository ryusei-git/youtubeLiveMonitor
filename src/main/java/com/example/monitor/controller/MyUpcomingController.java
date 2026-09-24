package com.example.monitor.controller;

import com.example.monitor.dto.UpcomingStreamResponse;
import com.example.monitor.service.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ログイン中の利用者が購読しているチャンネルの配信予定を返す API。
 *
 * <p>{@link MyChannelController} はクラス単位で {@code /api/my/channels} 配下に固定されているため、
 * {@code /api/my/upcoming} を置くために分けている。対象を本人に限る考え方は同じで、
 * 利用者をリクエストから指定する余地は作らない。
 *
 * <p>依存する {@link UserSubscriptionService} はプロファイルを問わず作られるため、
 * {@code @Profile("!cli")} は付けていない。
 */
@RestController
@RequiredArgsConstructor
public class MyUpcomingController {

    private final UserSubscriptionService userSubscriptionService;

    /**
     * 自分が購読しているチャンネルの配信予定を返す。
     *
     * @return 直近 7 日以内に開始予定の配信（開始予定の早い順）
     */
    @GetMapping("/api/my/upcoming")
    public List<UpcomingStreamResponse> listMyUpcomingStreams() {
        return userSubscriptionService.listMyUpcomingStreams();
    }
}
