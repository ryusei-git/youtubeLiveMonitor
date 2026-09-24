package com.example.monitor.service;

import com.example.monitor.dto.*;
import com.example.monitor.entity.*;
import com.example.monitor.platform.Platform;
import com.example.monitor.repository.OnlineVideoRepository;
import com.example.monitor.util.ThumbnailRetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** 収集とライブ検知が同時に同じ動画を見つけても、URLを一件だけ保存する。 */
@Service @RequiredArgsConstructor
public class OnlineVideoService {
    private final OnlineVideoRepository repository;
    private final UptimeTracker uptimeTracker;
    private final VideoCollectionTracker tracker;

    public Instant startedAt() {
        return uptimeTracker.getStartedAt().atZone(ZoneId.systemDefault()).toInstant();
    }

    /** リポジトリのコミットまでロックを持ち、別の収集経路との新規登録競合を防ぐ。 */
    public synchronized void capture(MonitoredChannel channel, OnlineVideoCandidate candidate) {
        var existing = repository.findById(candidate.key());
        if (existing.isEmpty() && candidate.publishedAt().isBefore(tracker.collectingSince(channel))) return;
        var video = existing.orElseGet(OnlineVideo::new);
        video.setId(candidate.key()); video.setChannel(channel);
        video.setTitle(candidate.title()); video.setWatchUrl(candidate.watchUrl());
        updateThumbnailUrl(video, candidate.thumbnailUrl()); video.setPublishedAt(candidate.publishedAt());
        if (video.getDiscoveredAt() == null) video.setDiscoveredAt(Instant.now());
        repository.save(video);
    }

    /** 通知フィルターや録画設定に関係なく、判定に成功した視聴先を残す。 */
    public synchronized void observe(MonitoredChannel channel, LiveStreamDetection detection) {
        if (detection.isDetectionFailed()) return;
        String key = channel.getPlatform().name() + "_" + (channel.getPlatform() == Platform.TWITCH ? "stream_" : "") + detection.videoId();
        repository.endOtherStreams(channel.getId(), detection.isLive() ? key : "");
        if (!detection.isLive()) return;
        var video = repository.findById(key).orElseGet(OnlineVideo::new);
        video.setId(key); video.setChannel(channel);
        video.setTitle(detection.title() == null ? channel.getChannelName() : detection.title());
        // VODが見つかった後に、現在のチャンネルURLへ戻してはいけない。
        if (video.getWatchUrl() == null || !video.getWatchUrl().contains("twitch.tv/videos/")) {
            video.setWatchUrl(detection.watchUrl());
            String thumbnail = channel.getPlatform() == Platform.YOUTUBE
                    ? "https://i.ytimg.com/vi/" + detection.videoId() + "/hqdefault.jpg"
                    : "https://static-cdn.jtvnw.net/previews-ttv/live_user_"
                        + URI.create(detection.watchUrl()).getPath().substring(1) + "-640x360.jpg";
            updateThumbnailUrl(video, thumbnail);
        }
        video.setLiveWatchUrl(detection.watchUrl());
        video.setLive(true); video.setLastObservedAt(Instant.now());
        // 待機所として判定済みでも、配信が始まった時点で配信済みの側へ移す。
        video.setContentKind(OnlineVideo.KIND_STREAM); video.setScheduledStartTime(null);
        if (video.getPublishedAt() == null) video.setPublishedAt(Instant.now());
        if (video.getDiscoveredAt() == null) video.setDiscoveredAt(Instant.now());
        repository.save(video);
    }

    /** 画像のURLが変われば、以前のURLに対する失敗は新しい画像に適用しない。 */
    private void updateThumbnailUrl(OnlineVideo video, String thumbnailUrl) {
        if (!Objects.equals(video.getThumbnailUrl(), thumbnailUrl)) {
            video.setThumbnailAttempts(0);
            video.setThumbnailNextAttemptAt(null);
        }
        video.setThumbnailUrl(thumbnailUrl);
    }

    public OnlineVideoResponse response(OnlineVideo video) {
        var channel = video.getChannel();
        boolean unknown = video.isLive() && (video.getLastObservedAt() == null
                || video.getLastObservedAt().isBefore(startedAt()) || channel.getConsecutiveDetectionFailures() > 0);
        String state = unknown ? "UNKNOWN" : video.isLive() ? "LIVE" : "VIDEO";
        boolean playable = channel.getPlatform() == Platform.YOUTUBE || video.getWatchUrl().contains("twitch.tv/videos/")
                || "LIVE".equals(state);
        String watchUrl = "LIVE".equals(state) && video.getLiveWatchUrl() != null ? video.getLiveWatchUrl() : video.getWatchUrl();
        return new OnlineVideoResponse(video.getId(), channel.getId(), channel.getChannelName(),
                channel.getPlatform().name(), video.getTitle(), watchUrl,
                "/api/videos/" + video.getId() + "/thumbnail", video.getPublishedAt(), video.getLastObservedAt(), state, playable,
                ThumbnailRetryPolicy.exhausted(video.getThumbnailAttempts()),
                video.getContentKind(), video.getScheduledStartTime());
    }
}
