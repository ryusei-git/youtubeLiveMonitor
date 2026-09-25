package com.example.monitor.service;

import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.OnlineVideoCandidate;
import com.example.monitor.dto.OnlineVideoResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.OnlineVideo;
import com.example.monitor.platform.Platform;
import com.example.monitor.repository.OnlineVideoRepository;
import com.example.monitor.util.ThumbnailRetryPolicy;
import com.example.monitor.util.YouTubeWatchUrl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 視聴できる動画・配信の一覧（{@code OnlineVideo}）を、収集と巡回の両方から記録し、画面向けの形に変える。
 *
 * <p>2 つの経路が同じ動画を同時に見つけても 1 件にまとめるため、登録を {@code synchronized} で直列にしている
 * （収集とライブ検知が同時に同じ動画を見つけても、URLを一件だけ保存する）。
 */
@Service @RequiredArgsConstructor
public class OnlineVideoService {
    /** 配信中で、アプリ起動後の巡回で観測できている。 */
    static final String STATE_LIVE = "LIVE";
    /** 配信中ではない（アーカイブ・投稿動画・配信予定）。 */
    static final String STATE_VIDEO = "VIDEO";
    /** 配信中の印はあるが、今も配信中かを確かめられていない。 */
    static final String STATE_UNKNOWN = "UNKNOWN";

    private final OnlineVideoRepository repository;
    private final UptimeTracker uptimeTracker;
    private final VideoCollectionTracker tracker;

    /**
     * アプリの起動時刻を返す。
     *
     * <p>配信中の印は前回の起動中に付いたまま残っていることがあるため、この時刻より後に観測したものだけを
     * 「確かめた配信中」として扱う（画面の状態と、一覧の配信中の絞り込みの両方で使う）。
     *
     * @return アプリの起動時刻
     */
    public Instant startedAt() {
        return uptimeTracker.getStartedAt().atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 収集で見つけた動画を記録する。
     *
     * <p>リポジトリのコミットまでロックを持ち、別の収集経路との新規登録競合を防ぐ。
     *
     * @param channel   動画のチャンネル
     * @param candidate 外部サービスから取ってきた動画。未登録で収集開始の境界より前に公開されたものは記録しない
     */
    public synchronized void capture(MonitoredChannel channel, OnlineVideoCandidate candidate) {
        var existing = repository.findById(candidate.key());
        if (existing.isEmpty() && candidate.publishedAt().isBefore(tracker.collectingSince(channel))) {
            return;
        }
        var video = existing.orElseGet(OnlineVideo::new);
        video.setId(candidate.key());
        video.setChannel(channel);
        video.setTitle(candidate.title());
        video.setWatchUrl(candidate.watchUrl());
        updateThumbnailUrl(video, candidate.thumbnailUrl());
        video.setPublishedAt(candidate.publishedAt());
        if (video.getDiscoveredAt() == null) {
            video.setDiscoveredAt(Instant.now());
        }
        repository.save(video);
    }

    /**
     * 巡回の検知結果を記録する。
     *
     * <p>通知フィルターや録画設定に関係なく、判定に成功した視聴先を残す。
     *
     * @param channel   巡回したチャンネル
     * @param detection 検知結果。判定に失敗していれば何もしない（配信中の印を外さない）
     */
    public synchronized void observe(MonitoredChannel channel, LiveStreamDetection detection) {
        if (detection.isDetectionFailed()) {
            return;
        }
        String key = channel.getPlatform().name() + "_" + (channel.getPlatform() == Platform.TWITCH ? "stream_" : "") + detection.videoId();
        repository.endOtherStreams(channel.getId(), detection.isLive() ? key : "");
        if (!detection.isLive()) {
            return;
        }
        var video = repository.findById(key).orElseGet(OnlineVideo::new);
        video.setId(key);
        video.setChannel(channel);
        video.setTitle(detection.title() == null ? channel.getChannelName() : detection.title());
        // VODが見つかった後に、現在のチャンネルURLへ戻してはいけない。
        if (!isTwitchVod(video.getWatchUrl())) {
            video.setWatchUrl(detection.watchUrl());
            String thumbnail = channel.getPlatform() == Platform.YOUTUBE
                    ? YouTubeWatchUrl.thumbnailOf(detection.videoId())
                    : "https://static-cdn.jtvnw.net/previews-ttv/live_user_"
                        + URI.create(detection.watchUrl()).getPath().substring(1) + "-640x360.jpg";
            updateThumbnailUrl(video, thumbnail);
        }
        video.setLiveWatchUrl(detection.watchUrl());
        video.setLive(true);
        video.setLastObservedAt(Instant.now());
        // 待機所として判定済みでも、配信が始まった時点で配信済みの側へ移す。
        video.setContentKind(OnlineVideo.KIND_STREAM);
        video.setScheduledStartTime(null);
        if (video.getPublishedAt() == null) {
            video.setPublishedAt(Instant.now());
        }
        if (video.getDiscoveredAt() == null) {
            video.setDiscoveredAt(Instant.now());
        }
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

    /**
     * 動画を画面へ返す形に変える。
     *
     * <p>エンティティを API に直接返さず、状態・再生できるか・視聴 URL をここで決めて、画面に判定を持たせない。
     *
     * @param video 動画。チャンネルを読み込めること
     * @return 画面へ返す形
     */
    public OnlineVideoResponse response(OnlineVideo video) {
        var channel = video.getChannel();
        String state = stateOf(video);
        boolean playable = channel.getPlatform() == Platform.YOUTUBE || isTwitchVod(video.getWatchUrl())
                || STATE_LIVE.equals(state);
        String watchUrl = STATE_LIVE.equals(state) && video.getLiveWatchUrl() != null ? video.getLiveWatchUrl() : video.getWatchUrl();
        return new OnlineVideoResponse(video.getId(), channel.getId(), channel.getChannelName(),
                channel.getPlatform().name(), channel.getPlatform().displayName(), video.getTitle(), watchUrl,
                "/api/videos/" + video.getId() + "/thumbnail", video.getPublishedAt(), video.getLastObservedAt(), state, playable,
                ThumbnailRetryPolicy.exhausted(video.getThumbnailAttempts()),
                video.getContentKind(), video.getScheduledStartTime());
    }

    /**
     * 配信中の印があっても、アプリ起動後にまだ観測していない、または判定に失敗し続けている
     * ときは、印が古いかもしれないので「配信中」と言い切らずに {@code UNKNOWN} を返す。
     */
    private String stateOf(OnlineVideo video) {
        if (!video.isLive()) {
            return STATE_VIDEO;
        }
        boolean unobserved = video.getLastObservedAt() == null || video.getLastObservedAt().isBefore(startedAt());
        if (unobserved || video.getChannel().getConsecutiveDetectionFailures() > 0) {
            return STATE_UNKNOWN;
        }
        return STATE_LIVE;
    }

    /** Twitch の VOD の URL は配信が終わっても再生でき、チャンネルの URL へ戻してはいけないため見分ける。 */
    private static boolean isTwitchVod(String watchUrl) {
        return watchUrl != null && watchUrl.contains("twitch.tv/videos/");
    }
}
