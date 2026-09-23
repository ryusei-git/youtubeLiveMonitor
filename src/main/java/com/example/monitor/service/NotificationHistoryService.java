package com.example.monitor.service;

import com.example.monitor.dto.NotificationOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.NotificationHistory;
import com.example.monitor.entity.NotificationHistory.NotificationResultType;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.NotificationHistoryRepository;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 通知履歴の記録と照会を担当する。
 *
 * <p>成功した通知だけでなく失敗した試行も残す。
 * 「通知が届かなかった」ときの原因追跡には、失敗の記録こそが必要になるため。
 */
@Service
@RequiredArgsConstructor
public class NotificationHistoryService {

    private final NotificationHistoryRepository notificationHistoryRepository;
    private final MonitoredChannelRepository monitoredChannelRepository;

    /**
     * 通知を試みた結果を履歴に残す。成功・失敗を問わず必ず 1 件記録する。
     *
     * @param channel    通知対象のチャンネル
     * @param videoId    通知対象の配信の動画 ID
     * @param videoTitle 通知時点での配信タイトル
     * @param outcome    送信結果
     */
    public void recordAttempt(MonitoredChannel channel, String videoId, String videoTitle, NotificationOutcome outcome) {
        NotificationHistory history = NotificationHistory.builder()
                .channel(channel)
                .videoId(videoId)
                .videoTitle(videoTitle)
                .status(outcome.successful() ? NotificationResultType.SUCCESS : NotificationResultType.FAILED)
                .errorMessage(outcome.errorMessage())
                .build();
        notificationHistoryRepository.save(history);
    }

    /**
     * 全チャンネルの通知履歴を新しい順に取得する。
     *
     * @param pageable ページ指定
     * @return 通知時刻の降順に並んだ履歴
     */
    public Page<NotificationHistory> findRecentHistory(Pageable pageable) {
        return notificationHistoryRepository.findAllByOrderByNotifiedAtDesc(pageable);
    }

    /**
     * 特定チャンネルの通知履歴を新しい順に取得する。
     *
     * @param channelRecordId 監視対象チャンネルの主キー（YouTube のチャンネル ID ではない）
     * @param pageable        ページ指定
     * @return 通知時刻の降順に並んだ履歴
     * @throws ChannelNotFoundException 指定 ID のチャンネルが存在しない場合
     */
    public Page<NotificationHistory> findHistoryByChannel(Long channelRecordId, Pageable pageable) {
        MonitoredChannel channel = monitoredChannelRepository.findById(channelRecordId)
                .orElseThrow(() -> new ChannelNotFoundException(channelRecordId));
        return notificationHistoryRepository.findByChannelOrderByNotifiedAtDesc(channel, pageable);
    }

    /**
     * 指定時刻より後の通知件数を数える。ダッシュボードの集計に使う。
     *
     * @param since 集計の起点となる時刻
     * @return 件数
     */
    public long countSince(LocalDateTime since) {
        return notificationHistoryRepository.countByNotifiedAtAfter(since);
    }

    /**
     * 指定時刻より後の「送信に失敗した」通知件数を数える。ダッシュボードの集計に使う。
     *
     * @param since 集計の起点となる時刻
     * @return 件数
     */
    public long countFailuresSince(LocalDateTime since) {
        return notificationHistoryRepository.countByStatusAndNotifiedAtAfter(
                NotificationResultType.FAILED, since);
    }

    /**
     * 条件を指定して通知履歴を検索する。
     *
     * <p><b>絞り込みは DB 側で行う。</b>1 ページ分を取得してからその中で絞ると、
     * 条件に合う履歴がページの外にあったときに<b>一覧から丸ごと消える</b>
     * （「失敗した通知を探す」という一番使いたい場面で取りこぼす）。
     *
     * @param channelId 対象チャンネルの主キー。全チャンネルなら {@code null}
     * @param status    通知結果。絞り込まないなら {@code null}
     * @param keyword   配信タイトルに含まれる文字列。絞り込まないなら {@code null}
     * @param since     通知時刻の下限。絞り込まないなら {@code null}
     * @param until     通知時刻の上限。絞り込まないなら {@code null}
     * @param pageable  ページ指定
     * @return 条件に一致した履歴
     */
    public Page<NotificationHistory> searchHistory(Long channelId, NotificationResultType status, String keyword,
                                                  LocalDateTime since, LocalDateTime until, Pageable pageable) {
        return notificationHistoryRepository.findAll((root, query, builder) -> {
            List<Predicate> filters = new ArrayList<>();
            if (channelId != null) {
                filters.add(builder.equal(root.get("channel").get("id"), channelId));
            }
            if (status != null) {
                filters.add(builder.equal(root.get("status"), status));
            }
            if (since != null) {
                filters.add(builder.greaterThanOrEqualTo(root.get("notifiedAt"), since));
            }
            if (until != null) {
                filters.add(builder.lessThanOrEqualTo(root.get("notifiedAt"), until));
            }
            if (keyword != null && !keyword.isBlank()) {
                // 大文字小文字を無視して部分一致させる（locate は 1 始まりで、見つからなければ 0）
                filters.add(builder.greaterThan(builder.locate(builder.lower(root.get("videoTitle")),
                        keyword.strip().toLowerCase(Locale.ROOT)), 0));
            }
            return builder.and(filters.toArray(Predicate[]::new));
        }, pageable);
    }
}
