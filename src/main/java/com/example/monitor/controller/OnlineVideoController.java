package com.example.monitor.controller;

import com.example.monitor.dto.OnlineVideoResponse;
import com.example.monitor.dto.PageResponse;
import com.example.monitor.entity.OnlineVideo;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.OnlineVideoRepository;
import com.example.monitor.repository.VideoThumbnailRepository;
import com.example.monitor.service.OnlineVideoService;
import com.example.monitor.service.VideoCollectionTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 一覧と画像の両方で購読を照合し、URLの直打ちでも他人のライブラリを読ませない。 */
@RestController @RequestMapping("/api/videos") @RequiredArgsConstructor
public class OnlineVideoController {
    /** 1 ページの件数の上限。一度に大量の行を読ませて応答を重くしないため。 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 検索語の長さの上限。極端に長い語で LIKE 検索を重くさせないため。 */
    private static final int MAX_KEYWORD_LENGTH = 200;
    /** 画面の配信予定は直近 1 週間分だけを並べる。 */
    private static final Duration UPCOMING_WINDOW = Duration.ofDays(7);

    private final OnlineVideoRepository repository;
    private final VideoThumbnailRepository thumbnails;
    private final OnlineVideoService service;
    private final MonitoredChannelRepository channels;
    private final VideoCollectionTracker tracker;

    @GetMapping("/viewer")
    public Map<String, Boolean> viewer(Authentication auth) {
        return Map.of("admin", admin(auth));
    }

    @GetMapping("/channels")
    public List<VideoCollectionTracker.Snapshot> channels(Authentication auth) {
        return channels.findLibraryChannels(admin(auth), auth.getName()).stream().map(tracker::snapshot).toList();
    }


    @GetMapping
    public PageResponse<OnlineVideoResponse> list(Authentication auth,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) Long channelId,
            @RequestParam(defaultValue = "false") boolean liveOnly, @RequestParam(required = false) String section) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || keyword.length() > MAX_KEYWORD_LENGTH) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        boolean admin = admin(auth);
        String user = auth.getName();
        var pageable = PageRequest.of(page, size);
        // 省略時はダッシュボード（liveOnly=true）が使う従来の検索のまま動きを変えない
        var videos = section == null
                ? repository.search(admin, user, channelId, liveOnly, service.startedAt(), keyword, pageable)
                : switch (section) {
                    case "now" -> repository.searchNow(admin, user, channelId, service.startedAt(),
                            Instant.now().plus(UPCOMING_WINDOW), keyword, pageable);
                    case "streams" -> repository.searchStreams(admin, user, channelId, service.startedAt(), keyword, pageable);
                    case "uploads" -> repository.searchUploads(admin, user, channelId, keyword, pageable);
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                };
        return PageResponse.from(videos.map(service::response));
    }

    @GetMapping("/{id}")
    public OnlineVideoResponse detail(@PathVariable String id, Authentication auth) {
        return service.response(visible(id, auth));
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String id, Authentication auth) {
        visible(id, auth);
        var thumbnail = thumbnails.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(thumbnail.getContentType())).body(thumbnail.getContent());
    }

    /** 存在しない画像や入力不正を、共通の想定外エラー処理へ渡さない。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException error) {
        String message = error.getStatusCode().value() == 404 ? "動画またはサムネイルが見つかりません" : "指定された条件が不正です";
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", message));
    }

    private OnlineVideo visible(String id, Authentication auth) {
        return repository.findVisible(id, admin(auth), auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
