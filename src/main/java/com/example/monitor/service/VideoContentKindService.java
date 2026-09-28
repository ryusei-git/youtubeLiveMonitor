package com.example.monitor.service;

import com.example.monitor.entity.OnlineVideo;
import com.example.monitor.platform.Platform;
import com.example.monitor.repository.OnlineVideoRepository;
import com.example.monitor.util.YouTubeVideoKindParser;
import com.example.monitor.util.YouTubeWatchUrl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 収集した動画を「配信予定・配信済み・投稿済み」に分ける。
 *
 * <p>RSS フィードは投稿動画・配信アーカイブ・待機所を区別せずに返すため、動画ページを 1 件ずつ見て判定する。
 * 判定できなかった動画は種類を空のまま残し、次の収集で再試行する（1 件の失敗で収集全体を止めない）。
 * 待機所と判定済みの動画で、動画ページが「再生できません」を返したもの（削除された・存在しない）は、消えた動画（{@link OnlineVideo#KIND_MISSING}）にして判定し直す対象から外す。消された待機所が配信予定の段に残り、収集のたびに動画ページを取り直し続けたため。
 */
@Service @RequiredArgsConstructor @Slf4j
public class VideoContentKindService {
    /** 1 回の収集で動画ページを取りに行く上限。待機所が溜まっても収集の周期を大きく延ばさないため。 */
    private static final int BATCH_SIZE = 50;
    private static final String YOUTUBE_KEY_PREFIX = Platform.YOUTUBE.name() + "_";
    /** {@code LiveStreamDetector} と同じ。簡易版の HTML には判定の目印が含まれないため。 */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final OnlineVideoRepository videos;
    private final HttpClient httpClient;

    /**
     * 種類（配信・投稿・待機所）が未判定か待機所の動画を、1 回 {@value #BATCH_SIZE} 件まで判定し直す。
     *
     * <p>上限を設ける理由は {@link #BATCH_SIZE} を参照。待機所を毎回見直すのは、配信が始まって
     * 終わると種類が変わるため。判定できなかった動画は種類をそのまま残し、次の収集で再試行する。
     * 消えた動画は種類が {@code UPCOMING} でも null でもなくなるので、次の収集から {@link OnlineVideoRepository#pendingContentKind} の対象に入らない。まだ種類が分からない（null の）動画には付けない。「再生できません」の文言は汎用で、YouTube 側の一時的な制限でも返るおそれがあり、見つけたばかりの公開動画をどの段からも消してしまうため。
     */
    public void classifyPending() {
        for (var video : videos.pendingContentKind(PageRequest.of(0, BATCH_SIZE))) {
            if (Thread.currentThread().isInterrupted()) return;
            // Twitch の VOD は配信の録画しか無いので、通信せずに決める。
            if (video.getChannel().getPlatform() == Platform.TWITCH) {
                videos.updateContentKind(video.getId(), OnlineVideo.KIND_STREAM, null);
                continue;
            }
            try {
                var result = YouTubeVideoKindParser.parse(fetch(video.getId()));
                // 消えた動画は、待機所と判定済みの動画にだけ付ける（まだ種類の分からない動画は、今までどおり判定できないとして残す）。
                boolean missing = result != null && OnlineVideo.KIND_MISSING.equals(result.kind());
                if (result == null || (missing && !OnlineVideo.KIND_UPCOMING.equals(video.getContentKind()))) {
                    log.warn("動画の種類を判定できません（次回再試行）: video={}", video.getId());
                    continue;
                }
                int updated = videos.updateContentKind(video.getId(), result.kind(), result.scheduledStartTime());
                if (updated > 0 && missing) {
                    log.info("動画ページが「再生できません」を返したため、消えた待機所として判定し直しを止めます: video={}", video.getId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception e) {
                log.warn("動画ページの取得に失敗しました（次回再試行）: video={}, reason={}", video.getId(), e.getMessage());
            }
        }
    }

    /** 200 以外は判定材料にしない（エラーページの本文を解析して誤判定しないため）。 */
    private String fetch(String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(YouTubeWatchUrl.of(key.substring(YOUTUBE_KEY_PREFIX.length()))))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", BROWSER_USER_AGENT)
                .GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }
}
