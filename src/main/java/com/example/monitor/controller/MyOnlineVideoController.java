package com.example.monitor.controller;

import com.example.monitor.repository.OnlineVideoRepository;
import com.example.monitor.security.FriendGate;
import com.example.monitor.service.OnlineVideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 利用者の画面の視聴画面（{@code /my/videos/watch/{id}}）が読む、録画していない動画 1 件の API。
 *
 * <p>同じ動画は {@code GET /api/videos/{id}} でも読めるが、利用者の画面が使う API は {@code /api/my} 配下に
 * そろえる。一覧と同じ購読の境界で照合し、URL を直接開かれても購読していない動画の有無を見分けさせない。
 *
 * <p>Web の画面からしか使わないため {@code @Profile("!cli")} を付ける
 * （{@code docs/pitfalls.md}「cli プロファイルで作られない Bean に依存するコントローラー…」）。
 */
@RestController
@Profile("!cli")
@RequiredArgsConstructor
public class MyOnlineVideoController {

    private final OnlineVideoRepository repository;
    private final OnlineVideoService service;

    /**
     * 見てよい動画を 1 件返す。
     *
     * @param id   動画の主キー（{@code OnlineVideoResponse} の {@code id} の形）
     * @param auth ログイン中の利用者。管理者はすべてのチャンネル、一般の利用者は購読しているチャンネルだけを見られる
     * @return 動画（200）。無い・見る権限が無いときは 404 に {@code error} の文言
     */
    @GetMapping("/api/my/videos/{id}")
    public ResponseEntity<?> video(@PathVariable String id, Authentication auth) {
        return repository.findVisible(id, FriendGate.isAdmin(auth.getAuthorities()), auth.getName())
                .<ResponseEntity<?>>map(video -> ResponseEntity.ok(service.response(video)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "動画が見つかりません")));
    }
}
