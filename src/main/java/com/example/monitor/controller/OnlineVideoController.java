package com.example.monitor.controller;

import com.example.monitor.dto.*;
import com.example.monitor.entity.OnlineVideo;
import com.example.monitor.repository.*;
import com.example.monitor.service.OnlineVideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 一覧と画像の両方で購読を照合し、URLの直打ちでも他人のライブラリを読ませない。 */
@RestController @RequestMapping("/api/videos") @RequiredArgsConstructor
public class OnlineVideoController {
    private final OnlineVideoRepository repository;
    private final VideoThumbnailRepository thumbnails;
    private final OnlineVideoService service;
    private final MonitoredChannelRepository channels;
    private final com.example.monitor.service.VideoCollectionTracker tracker;

    @GetMapping("/viewer")
    public java.util.Map<String, Boolean> viewer(Authentication auth) {
        return java.util.Map.of("admin", admin(auth));
    }

    @GetMapping("/channels")
    public java.util.List<com.example.monitor.service.VideoCollectionTracker.Snapshot> channels(Authentication auth) {
        return channels.findLibraryChannels(admin(auth), auth.getName()).stream().map(tracker::snapshot).toList();
    }


    @GetMapping
    public PageResponse<OnlineVideoResponse> list(Authentication auth,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) Long channelId,
            @RequestParam(defaultValue = "false") boolean liveOnly, @RequestParam(required = false) String section) {
        if (page < 0 || size < 1 || size > 100 || keyword.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        boolean admin = admin(auth);
        String user = auth.getName();
        var pageable = PageRequest.of(page, size);
        // 省略時はダッシュボード（liveOnly=true）が使う従来の検索のまま動きを変えない
        var videos = section == null
                ? repository.search(admin, user, channelId, liveOnly, service.startedAt(), keyword, pageable)
                : switch (section) {
                    // 画面の「配信予定」は直近 1 週間分だけを並べる
                    case "now" -> repository.searchNow(admin, user, channelId, service.startedAt(),
                            java.time.Instant.now().plus(java.time.Duration.ofDays(7)), keyword, pageable);
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
    public ResponseEntity<java.util.Map<String, String>> handleStatus(ResponseStatusException error) {
        String message = error.getStatusCode().value() == 404 ? "動画またはサムネイルが見つかりません" : "指定された条件が不正です";
        return ResponseEntity.status(error.getStatusCode()).body(java.util.Map.of("error", message));
    }

    private OnlineVideo visible(String id, Authentication auth) {
        return repository.findVisible(id, admin(auth), auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
