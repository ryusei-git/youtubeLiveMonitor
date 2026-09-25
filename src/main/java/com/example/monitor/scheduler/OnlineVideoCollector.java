package com.example.monitor.scheduler;

import com.example.monitor.dto.OnlineVideoCandidate;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.Platform;
import com.example.monitor.platform.twitch.TwitchApiClient;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.service.OnlineVideoService;
import com.example.monitor.service.VideoCollectionTracker;
import com.example.monitor.service.VideoContentKindService;
import com.example.monitor.service.VideoThumbnailService;
import com.example.monitor.service.YouTubeUploadsClient;
import com.example.monitor.service.YouTubeVideoFeedClient;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 監視チャンネルの投稿動画・配信アーカイブを定期的に集め、動画ライブラリへ記録する。
 * <p>通常動画の取得待ちでライブ検知・通知を遅らせないよう、独立したスレッドで収集する。
 */
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
    /** 確認用の起動（{@code bin/preview.sh}）では収集しない。本番と同じ動画を二重に取りに行かないため。 */
    @Value("${monitor.scheduling.enabled:true}")
    private boolean schedulingEnabled = true;

    /**
     * 動画の収集を仮想スレッドで 1 回始める。
     * <p>{@code @Scheduled} のスレッドはライブ検知の定期実行と共有しているため、時間のかかる収集はそこで走らせず仮想スレッドへ逃がす。前回の収集がまだ終わっていなければ（{@code running} が立っていれば）何もしない。確認用の起動（{@code monitor.scheduling.enabled=false}）でも何もしない。
     */
    @Scheduled(fixedDelayString = "${monitor.video-collection-interval-ms:600000}", initialDelay = 10000)
    public void schedule() {
        if (!schedulingEnabled || !running.compareAndSet(false, true)) return;
        worker = Thread.startVirtualThread(() -> {
            try {
                collect();
            } catch (RuntimeException e) {
                log.error("動画収集の実行に失敗しました。次回再試行します", e);
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * 全チャンネルの新着動画を 1 回集め、サムネイルの取得と種類の判定まで済ませる。
     * <p>YouTube は公開フィードを先に試し、取れなければ公式 API（uploads 再生リスト）へ切り替える。フィードはクォータを使わないため。1 チャンネルの失敗で残りのチャンネルを止めないよう、失敗はチャンネルごとに記録して次へ進む。スレッドが割り込まれたら（{@link #stop()}）、次のチャンネルへ進まずに終える。
     */
    public void collect() {
        var all = channels.findAll();
        refreshTwitchLogins(all);
        for (var channel : all) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var since = tracker.querySince(channel);
                var candidates = channel.getPlatform() == Platform.YOUTUBE
                        ? fetchYouTube(channel.getYoutubeChannelId(), since) : twitch.fetchRecentVideos(channel.getYoutubeChannelId());
                for (var candidate : candidates) videos.capture(channel, candidate);
                tracker.checked(channel, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                try {
                    tracker.checked(channel, false);
                } catch (RuntimeException saveFailure) {
                    log.warn("削除済み等の理由で取得状態を保存できません: channel={}", channel.getId(), saveFailure);
                }
                log.warn("投稿動画の取得失敗（ライブ監視は継続）: channel={}, reason={}", channel.getYoutubeChannelId(), e.getMessage());
            }
        }
        thumbnails.captureMissing();
        // 同じ回で入った新しい動画も、次の収集を待たずに種類を判定する。
        contentKinds.classifyPending();
    }

    private List<OnlineVideoCandidate> fetchYouTube(String channelId, Instant since)
            throws IOException, InterruptedException {
        try {
            return youtube.fetch(channelId);
        } catch (IOException | IllegalArgumentException | DateTimeException e) {
            // APIのエラーURLにはキーが含まれうるので、例外本文をログに出さない。
            try {
                return youtubeUploads.fetch(channelId, since);
            } catch (IOException failure) {
                throw new IOException("フィード・公式APIとも新着動画を取得できません（設定・クォータを確認してください）");
            }
        }
    }

    /** ログイン名は改名されうるので、リンク用に毎回取り直す。失敗しても前の値を残し、収集は続ける。 */
    private void refreshTwitchLogins(List<MonitoredChannel> all) {
        var twitchChannels = all.stream().filter(c -> c.getPlatform() == Platform.TWITCH).toList();
        if (twitchChannels.isEmpty()) return;
        try {
            var logins = new HashMap<String, String>();
            for (var user : twitch.findUsersByIds(twitchChannels.stream().map(c -> c.getYoutubeChannelId()).toList())) {
                logins.put(user.id(), user.login());
            }
            for (var channel : twitchChannels) {
                String login = logins.get(channel.getYoutubeChannelId());
                if (login != null && !login.isBlank() && !login.equals(channel.getChannelLogin())) {
                    channels.updateChannelLogin(channel.getId(), login);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Twitch のログイン名を取得できません（収集は継続）: reason={}", e.getMessage());
        }
    }

    /**
     * アプリの終了時に、収集中のスレッドを割り込みで止める。
     * <p>収集が終わるまで終了処理を待たせないため。{@link #collect()} はチャンネルごとに割り込みを確かめて終える。
     */
    @PreDestroy
    public void stop() {
        if (worker != null) worker.interrupt();
    }
}
