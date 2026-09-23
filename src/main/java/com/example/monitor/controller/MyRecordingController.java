package com.example.monitor.controller;

import com.example.monitor.dto.PageResponse;
import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.service.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ログイン中の利用者が見られる録画を返す API。
 *
 * <p>管理者向けの {@code /api/recordings} とは<b>別の窓口</b>として分けている。
 * あちらは全チャンネルの録画を返し、削除もできる。こちらは
 * <b>自分が購読しているチャンネルの録画だけ</b>を返し、削除はできない。
 *
 * <p>対象の利用者はパスから指定できず、常にログイン中の本人になる
 * （{@link MyChannelController} と同じ考え方）。
 */
@RestController
@RequestMapping("/api/my/recordings")
@RequiredArgsConstructor
public class MyRecordingController {

    private final UserSubscriptionService userSubscriptionService;

    /**
     * 自分が購読しているチャンネルの録画を新しい順に返す。
     *
     * @param page ページ番号（0 始まり）
     * @param size 1 ページの件数
     * @return 録画の一覧
     */
    @GetMapping
    public PageResponse<RecordingResponse> listMyRecordings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(
                userSubscriptionService.listMyRecordings(PageRequest.of(page, size)));
    }
}
