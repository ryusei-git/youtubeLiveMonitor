package com.example.monitor.scheduler;

import com.example.monitor.platform.Platform;
import com.example.monitor.platform.twitch.TwitchApiClient;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.service.*;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

/** 通常動画の取得待ちでライブ検知・通知を遅らせないよう、独立したスレッドで収集する。 */
@Component @Profile("!cli") @RequiredArgsConstructor @Slf4j
public class OnlineVideoCollector {
    private final MonitoredChannelRepository channels;
    private final YouTubeVideoFeedClient youtube;
    private final YouTubeUploadsClient youtubeUploads;
    private final TwitchApiClient twitch;
    private final OnlineVideoService videos;
    private final VideoThumbnailService thumbnails;
    private final VideoContentKindService contentKinds;
    private final VideoCollectionTracker tracker;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    @Scheduled(fixedDelayString = "${monitor.video-collection-interval-ms:600000}", initialDelay = 10000)
    public void schedule() {
        if (!running.compareAndSet(false, true)) return;
        worker = Thread.startVirtualThread(() -> {
            try { collect(); }
            catch (RuntimeException e) { log.error("動画収集の実行に失敗しました。次回再試行します", e); }
            finally { running.set(false); }
        });
    }

    public void collect() {
        for (var channel : channels.findAll()) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var since = tracker.querySince(channel);
                var candidates = channel.getPlatform() == Platform.YOUTUBE
                        ? fetchYouTube(channel.getYoutubeChannelId(), since) : twitch.fetchRecentVideos(channel.getYoutubeChannelId());
                for (var candidate : candidates) videos.capture(channel, candidate);
                tracker.checked(channel, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception e) {
                try { tracker.checked(channel, false); }
                catch (RuntimeException ignored) { log.warn("削除済み等の理由で取得状態を保存できません: channel={}", channel.getId()); }
                log.warn("投稿動画の取得失敗（ライブ監視は継続）: channel={}, reason={}", channel.getYoutubeChannelId(), e.getMessage());
            }
        }
        thumbnails.captureMissing();
        // 同じ回で入った新しい動画も、次の収集を待たずに種類を判定する。
        contentKinds.classifyPending();
    }

    private java.util.List<com.example.monitor.dto.OnlineVideoCandidate> fetchYouTube(String channelId, java.time.Instant since)
            throws java.io.IOException, InterruptedException {
        try { return youtube.fetch(channelId); }
        catch (java.io.IOException | IllegalArgumentException e) {
            // APIのエラーURLにはキーが含まれうるので、例外本文をログに出さない。
            try { return youtubeUploads.fetch(channelId, since); }
            catch (java.io.IOException failure) { throw new java.io.IOException("フィード・公式APIとも新着動画を取得できません（設定・クォータを確認してください）"); }
        }
    }

    @PreDestroy
    public void stop() {
        if (worker != null) worker.interrupt();
    }
}
