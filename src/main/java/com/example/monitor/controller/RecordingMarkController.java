package com.example.monitor.controller;

import com.example.monitor.dto.RecordingFavoriteRequest;
import com.example.monitor.dto.RecordingMarkResponse;
import com.example.monitor.dto.RecordingWatchedRequest;
import com.example.monitor.service.RecordingHistoryService;
import com.example.monitor.service.RecordingMarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final RecordingHistoryService recordingHistoryService;

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

    /**
     * 再生回数（全員の合計）に 1 を足す。画面が再生を始めたときに 1 回だけ呼ぶ。
     *
     * <p>回数は印と違って利用者ごとではないが、{@code /watched} と同じ場所から呼ばれるため
     * パスをそろえてここに置いている。
     *
     * @param id 録画の主キー
     * @return 本文なしの 204
     */
    @PostMapping("/play")
    public ResponseEntity<Void> countPlay(@PathVariable Long id) {
        recordingHistoryService.countPlay(id);
        return ResponseEntity.noContent().build();
    }
}
