package com.example.monitor.controller;

import com.example.monitor.dto.SoundMarkRequest;
import com.example.monitor.dto.SoundMarkResponse;
import com.example.monitor.service.SoundMarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/**
 * 録画に付ける音の印（耳キスなど）の API。利用者の再生画面から使う（#459）。
 *
 * <p>{@link MyRecordingController} と同じ {@code /api/my/recordings} 配下に置くが、別クラスにしている。
 * あちらは録画と利用者ごとの印（視聴済み・お気に入り）で、こちらは全員で共有する「録画の中の時点」の印と、
 * 扱うものが違うため。{@code /api/my/**} はログインしていれば使える（{@code SecurityConfig}）。
 *
 * <p>印を付ける・消す利用者はパスから指定できず、常にログイン中の本人になる
 * （{@link MyRecordingController} と同じ考え方）。依存するサービスはプロファイルを問わず
 * 作られるので、{@code @Profile("!cli")} は付けていない（{@link MyRecordingController} と同じ）。
 */
@RestController
@RequestMapping("/api/my/recordings/{recordingId}/sound-marks")
@RequiredArgsConstructor
public class MySoundMarkController {

    private final SoundMarkService soundMarkService;

    /**
     * 録画に付いた、ある種類の印を位置の順に返す。ほかの人の印も含む。
     *
     * <p>{@code kind} を省略可にしているのは、省略されたときに枠組みの 500 ではなく
     * 種類が不正の 400 を返すため（サービスが確かめる）。
     *
     * @param recordingId 録画の主キー
     * @param kind        種類（{@code EAR_KISS}）
     * @return 印の一覧
     * @throws IllegalArgumentException 種類が無い・不正なとき（400）
     * @throws com.example.monitor.exception.RecordingNotFoundException 録画が無いとき（404）
     */
    @GetMapping
    public List<SoundMarkResponse> list(@PathVariable Long recordingId, @RequestParam(required = false) String kind) {
        return soundMarkService.list(recordingId, kind);
    }

    /**
     * 印を付ける。同じ人が同じ種類の印を前後 1 秒以内に付けていれば、それを返す（二度押し対策）。
     *
     * @param recordingId 録画の主キー
     * @param request     種類と位置
     * @return 作った印。二度押しなら前の印
     * @throws IllegalArgumentException 種類が不正、位置が無い・負・録画の長さを超えるとき（400）
     * @throws com.example.monitor.exception.RecordingNotFoundException 録画が無いとき（404）
     */
    @PostMapping
    public SoundMarkResponse add(@PathVariable Long recordingId, @RequestBody SoundMarkRequest request) {
        return soundMarkService.add(recordingId, request);
    }

    /**
     * 本人が付けた印を消す。
     *
     * @param recordingId 録画の主キー
     * @param markId      印の主キー
     * @return 本文なしの 204
     * @throws com.example.monitor.exception.RecordingNotFoundException 録画が無いとき（404）
     * @throws com.example.monitor.exception.SoundMarkNotFoundException 印が無い・別の録画の印・他人の印のとき（404）
     */
    @DeleteMapping("/{markId}")
    public ResponseEntity<Void> delete(@PathVariable Long recordingId, @PathVariable Long markId) {
        soundMarkService.delete(recordingId, markId);
        return ResponseEntity.noContent().build();
    }

    /**
     * パスの番号の変換失敗を共通の 500 処理へ渡さず、内部の型名を含めない 400 にする
     * （{@link MyRecordingController} と同じ）。
     *
     * @param exception パスの番号の変換失敗
     * @return 入力誤りの応答
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPath(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "録画・印の番号は数で指定してください"));
    }
}
