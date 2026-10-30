package com.example.monitor.service;

import com.example.monitor.dto.RecordingChannelResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 利用者のアーカイブを「チャンネルから選ぶ」ための、チャンネルごとの録画の要約を作る。
 *
 * <p>録画の一覧（{@link RecordingHistoryService#search}）をチャンネルで絞るだけでは、どのチャンネルに
 * 録画があるのか・最後にいつ録ったのかを、チャンネルを 1 つずつ選ばないと知れない。件数・最新の録画・
 * サムネイルをチャンネルごとに 1 回の集計で返し、一覧のカードとチャンネルページの見出しに使う。
 *
 * <p>対象は全チャンネル（購読していないチャンネルも含む）。アーカイブは購読していないチャンネルの録画も
 * 見られる（#419）ため、ここで購読に絞ると、アーカイブにある録画へチャンネルからたどれなくなる。
 */
@Service
@RequiredArgsConstructor
public class RecordingChannelService {

    /** 数える録画の状態。利用者のアーカイブは再生できる録画だけを出すので、件数もそれにそろえる（#228）。 */
    private static final List<RecordingStatus> PLAYABLE = List.of(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL);

    private final RecordingRepository recordingRepository;
    private final MonitoredChannelRepository monitoredChannelRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final AppUserRepository appUserRepository;

    /**
     * 全チャンネルの要約を、最新の録画が新しい順に返す。
     *
     * <p>録画の無いチャンネルも返し、末尾に名前順で並べる。省くと、購読して自動録画を待っているチャンネルが
     * 一覧から消え、録画が無いのか一覧の不具合なのかが分からないため。
     *
     * @param username ログイン中の利用者名。購読しているかを添えるために使う
     * @return チャンネルの要約
     */
    @Transactional(readOnly = true)
    public List<RecordingChannelResponse> list(String username) {
        Map<Long, Object[]> summaries = summaries();
        Map<Long, String> thumbnails = latestThumbnails();
        Set<Long> subscribed = subscribedChannelIds(username);
        return monitoredChannelRepository.findAll().stream()
                .map(channel -> toResponse(channel, summaries, thumbnails, subscribed))
                .sorted(Comparator.comparing(RecordingChannelResponse::latestStartedAt,
                                Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
                        .thenComparing(RecordingChannelResponse::channelName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * 1 チャンネルの要約を返す。チャンネルページの見出しに使う。
     *
     * <p>集計は全チャンネル分をまとめて引き、その中から選ぶ。1 チャンネル用の集計を別に持つと、
     * 一覧とチャンネルページで数え方がずれうるため（登録数は数十件なので、まとめて引いても重くない）。
     *
     * @param username  ログイン中の利用者名
     * @param channelId チャンネルの主キー
     * @return チャンネルの要約
     * @throws ChannelNotFoundException チャンネルが無い場合
     */
    @Transactional(readOnly = true)
    public RecordingChannelResponse get(String username, Long channelId) {
        MonitoredChannel channel = monitoredChannelRepository.findById(channelId)
                .orElseThrow(() -> new ChannelNotFoundException(channelId));
        return toResponse(channel, summaries(), latestThumbnails(), subscribedChannelIds(username));
    }

    /**
     * @return チャンネルの主キーから {@link RecordingRepository#summarizeByChannel} の 1 行への対応
     */
    private Map<Long, Object[]> summaries() {
        Map<Long, Object[]> summaries = new HashMap<>();
        for (Object[] row : recordingRepository.summarizeByChannel(PLAYABLE)) {
            summaries.put((Long) row[0], row);
        }
        return summaries;
    }

    /**
     * @return チャンネルの主キーからサムネイルへの対応。開始時刻が同じ録画が並んだときは主キーの大きい方を採る
     */
    private Map<Long, String> latestThumbnails() {
        return recordingRepository.findLatestWithThumbnailByChannel(PLAYABLE).stream()
                .sorted(Comparator.comparing(Recording::getId))
                .collect(Collectors.toMap(r -> r.getChannel().getId(), Recording::getThumbnailPath,
                        (older, newer) -> newer));
    }

    /**
     * @param username ログイン中の利用者名
     * @return 購読しているチャンネルの主キー。利用者が見つからなければ空
     */
    private Set<Long> subscribedChannelIds(String username) {
        return appUserRepository.findByUsername(username)
                .map(user -> userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(user).stream()
                        .map(s -> s.getChannel().getId())
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private static RecordingChannelResponse toResponse(MonitoredChannel channel, Map<Long, Object[]> summaries,
                                                       Map<Long, String> thumbnails, Set<Long> subscribed) {
        Object[] row = summaries.get(channel.getId());
        return RecordingChannelResponse.of(channel, subscribed.contains(channel.getId()),
                row == null ? 0 : ((Number) row[1]).longValue(),
                row == null ? 0 : ((Number) row[2]).longValue(),
                row == null ? null : (LocalDateTime) row[3],
                thumbnails.get(channel.getId()));
    }
}
