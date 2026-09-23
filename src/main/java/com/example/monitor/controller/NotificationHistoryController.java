package com.example.monitor.controller;

import com.example.monitor.dto.NotificationHistoryResponse;
import com.example.monitor.dto.PageResponse;
import com.example.monitor.entity.NotificationHistory;
import com.example.monitor.service.NotificationHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知履歴を参照する REST API。
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationHistoryController {

    private final NotificationHistoryService notificationHistoryService;

    /**
     * 通知履歴を新しい順に取得する。成功した通知も失敗した通知も含まれる。
     *
     * @param channelId 特定チャンネルに絞り込む場合はその主キー。省略すると全チャンネルが対象
     * @param page      ページ番号（0 始まり）
     * @param size      1 ページあたりの件数
     * @return 通知時刻の降順に並んだ履歴
     */
    public PageResponse<NotificationHistoryResponse> getHistory(
            @RequestParam(required = false) Long channelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(page, size);

        Page<NotificationHistory> history = (channelId != null)
                ? notificationHistoryService.findHistoryByChannel(channelId, pageRequest)
                : notificationHistoryService.findRecentHistory(pageRequest);

        // Page をそのまま返すと JSON 構造が Spring の実装依存になる（PageResponse の JavaDoc 参照）
        return PageResponse.from(history.map(NotificationHistoryResponse::from));
    }

    /**
     * 条件を指定して通知履歴を検索する。
     *
     * <p>絞り込みは<b>DB 側</b>で行う（{@link NotificationHistoryService#searchHistory} 参照）。
     * 条件が 1 つも無ければ、並び順を含めて従来どおりの一覧をそのまま返す。
     *
     * @param channelId 対象チャンネルの主キー。全チャンネルなら省略
     * @param page      ページ番号（0 始まり）
     * @param size      1 ページあたりの件数
     * @param status    通知結果
     * @param keyword   配信タイトルに含まれる文字列
     * @param since     通知時刻の下限
     * @param until     通知時刻の上限
     * @return 条件に一致した履歴
     */
    @GetMapping
    public PageResponse<NotificationHistoryResponse> searchHistory(
            @RequestParam(required = false) Long channelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) NotificationHistory.NotificationResultType status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until) {

        if (since != null && until != null && since.isAfter(until)) {
            throw new IllegalArgumentException("期間の開始は終了以前にしてください");
        }
        // 件数は呼び出し側の指定をそのまま信じない（極端な値で DB を引かせないため）
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));

        if (status == null && (keyword == null || keyword.isBlank()) && since == null && until == null) {
            return getHistory(channelId, safePage, safeSize);
        }

        PageRequest request = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "notifiedAt"));
        return PageResponse.from(
                notificationHistoryService.searchHistory(channelId, status, keyword, since, until, request)
                        .map(NotificationHistoryResponse::from));
    }
}
