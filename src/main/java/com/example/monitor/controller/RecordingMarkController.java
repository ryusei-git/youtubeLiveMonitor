package com.example.monitor.controller;

import com.example.monitor.dto.RecordingFavoriteRequest;
import com.example.monitor.dto.RecordingMarkResponse;
import com.example.monitor.dto.RecordingWatchedRequest;
import com.example.monitor.service.RecordingMarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ログイン中の利用者が録画に付ける「視聴済み」「お気に入り」の API。
 *
 * <p>{@code RecordingController} と同じ {@code /api/recordings} 配下に置くが別クラスにしている。
 * あちらは録画そのもの（全員共通）の操作で、こちらは利用者ごとの印の操作と責務が違うため。
 * パスが {@code /api/recordings/**} なので、アクセスできるのは今の設定どおり管理者に限られる。
 *
 * <p>Web でしか使わないため {@code @Profile("!cli")} を付けている（{@code docs/pitfalls.md} 参照）。
 * 存在しない録画の id は {@code RecordingNotFoundException} 経由で 404 になる。
 */
@RestController
@RequestMapping("/api/recordings/{id}")
@RequiredArgsConstructor
@Profile("!cli")
public class RecordingMarkController {

    private final RecordingMarkService recordingMarkService;

    /**
     * 視聴済みを切り替える。
     *
     * @param id      録画の主キー
     * @param request 視聴済みにするか
     * @return 変更後の印
     */
    @PutMapping("/watched")
    public RecordingMarkResponse setWatched(@PathVariable Long id, @RequestBody RecordingWatchedRequest request) {
        return recordingMarkService.setWatched(id, request.watched());
    }

    /**
     * お気に入りを切り替える。
     *
     * @param id      録画の主キー
     * @param request お気に入りにするか
     * @return 変更後の印
     */
    @PutMapping("/favorite")
    public RecordingMarkResponse setFavorite(@PathVariable Long id, @RequestBody RecordingFavoriteRequest request) {
        return recordingMarkService.setFavorite(id, request.favorite());
    }
}
