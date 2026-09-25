package com.example.monitor.service;

import com.example.monitor.entity.VideoThumbnail;
import com.example.monitor.entity.OnlineVideo;
import com.example.monitor.repository.*;
import com.example.monitor.util.ThumbnailRetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 収集した動画のサムネイル画像を取って DB に保存する。
 *
 * <p>サムネイルだけを保存する。画像の障害で動画URLの収集まで失敗させない。
 */
@Service @RequiredArgsConstructor @Slf4j
public class VideoThumbnailService {
    private final OnlineVideoRepository videos;
    private final VideoThumbnailRepository thumbnails;
    private final HttpClient httpClient;

    /**
     * サムネイルの無い動画の画像を取って保存する。
     *
     * <p>1 回 100 件までにしているのは、動画が溜まっていても収集の 1 回を大きく延ばさないため。
     * 対象は再試行の上限と次の再試行時刻（{@link ThumbnailRetryPolicy}）を満たすものだけで、
     * 失敗は例外にせず記録して次の動画へ進む。
     */
    public void captureMissing() {
        for (var video : videos.eligibleWithoutThumbnail(
                Instant.now(), ThumbnailRetryPolicy.MAX_ATTEMPTS, PageRequest.of(0, 100))) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                if (!capture(video)) recordFailure(video);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception e) {
                log.warn("サムネイル取得失敗: video={}, reason={}", video.getId(), e.getMessage());
                recordFailure(video);
            }
        }
    }

    /** 画像の成否だけを返し、視聴URLや動画の収集状態には触れない。 */
    private boolean capture(OnlineVideo video) throws Exception {
        URI uri = URI.create(video.getThumbnailUrl());
        if (!"https".equals(uri.getScheme())
                || !Set.of("i.ytimg.com", "static-cdn.jtvnw.net").contains(uri.getHost())) return false;
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            String type = response.headers().firstValue("Content-Type").orElse("").split(";")[0];
            if (response.statusCode() != 200 || !Set.of("image/jpeg", "image/png", "image/webp").contains(type)) return false;
            byte[] bytes = body.readNBytes(2_000_001);
            if (bytes.length == 0 || bytes.length > 2_000_000) return false;
            var thumbnail = new VideoThumbnail();
            thumbnail.setId(video.getId()); thumbnail.setVideo(video); thumbnail.setContentType(type); thumbnail.setContent(bytes);
            thumbnails.save(thumbnail);
            return true;
        }
    }

    /** 失敗をDBに記録し、次の巡回や再起動で同じ画像を即座に拾わないようにする。 */
    private void recordFailure(OnlineVideo video) {
        int attempts = video.getThumbnailAttempts() + 1;
        Instant nextAttemptAt = ThumbnailRetryPolicy.nextAttemptAt(video.getId(), attempts, Instant.now());
        int updated = videos.recordThumbnailFailure(video.getId(), video.getThumbnailUrl(),
                video.getThumbnailAttempts(), attempts, nextAttemptAt);
        if (updated > 0 && ThumbnailRetryPolicy.exhausted(attempts)) {
            log.warn("サムネイルの再試行上限に達しました: video={}, attempts={}", video.getId(), attempts);
        }
    }
}
