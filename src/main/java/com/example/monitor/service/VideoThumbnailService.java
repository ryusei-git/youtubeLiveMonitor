package com.example.monitor.service;

import com.example.monitor.entity.VideoThumbnail;
import com.example.monitor.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** サムネイルだけを保存する。画像の障害で動画URLの収集まで失敗させない。 */
@Service @RequiredArgsConstructor @Slf4j
public class VideoThumbnailService {
    private final OnlineVideoRepository videos;
    private final VideoThumbnailRepository thumbnails;
    private final HttpClient httpClient;

    public void captureMissing() {
        for (var video : videos.withoutThumbnail(PageRequest.of(0, 100))) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                URI uri = URI.create(video.getThumbnailUrl());
                if (!"https".equals(uri.getScheme()) || !Set.of("i.ytimg.com", "static-cdn.jtvnw.net").contains(uri.getHost())) continue;
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    String type = response.headers().firstValue("Content-Type").orElse("").split(";")[0];
                    if (response.statusCode() != 200 || !Set.of("image/jpeg", "image/png", "image/webp").contains(type)) continue;
                    byte[] bytes = body.readNBytes(2_000_001);
                    if (bytes.length == 0 || bytes.length > 2_000_000) continue;
                    var thumbnail = new VideoThumbnail();
                    thumbnail.setId(video.getId()); thumbnail.setVideo(video); thumbnail.setContentType(type); thumbnail.setContent(bytes);
                    thumbnails.save(thumbnail);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception e) {
                log.warn("サムネイル取得失敗: video={}, reason={}", video.getId(), e.getMessage());
            }
        }
    }
}
