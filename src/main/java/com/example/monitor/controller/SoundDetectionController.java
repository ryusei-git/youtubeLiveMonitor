package com.example.monitor.controller;

import com.example.monitor.dto.SoundDetectionRunResponse;
import com.example.monitor.service.SoundDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 録画を指定して、耳キスなどの検出を今すぐ始め、その実行記録を見る管理者の API（Issue #470）。
 *
 * <p>{@code /api/recordings/**} は管理者だけが使える（{@code SecurityConfig}）。検出は 1 本で CPU を数十秒使うため、
 * 利用者には開かない。
 *
 * <p>依存する {@link SoundDetectionService} はプロファイルを問わず作られるので、{@code @Profile("!cli")} は
 * 付けていない（{@link MySoundCandidateController} と同じ）。
 */
@RestController
@RequestMapping("/api/recordings/{recordingId}/sound-detection")
@RequiredArgsConstructor
public class SoundDetectionController {

    private final SoundDetectionService soundDetectionService;

    /**
     * 録画の検出を今すぐ始める。見回りと同じ排他を通る。
     *
     * <p>返すのは 202 Accepted。検出は数十秒かかるので終わりを待たず、受け付けたことだけを返す
     * （結果は {@link #get} で見る。{@link DownloadController} と同じ考え方）。
     *
     * @param recordingId 録画の主キー
     * @param kind        種類（{@code EAR_KISS}）
     * @param force       今の版で検出済みでもやり直すか
     * @return 本文なしの 202
     * @throws IllegalArgumentException 種類が無い・不正なとき（400）
     * @throws com.example.monitor.exception.RecordingNotFoundException 録画が無いとき（404）
     * @throws com.example.monitor.exception.SoundDetectionConflictException
     *         再生できない・長さが分からない録画のとき、検出が走っているとき、今の版で検出済みで {@code force} が無いとき（409）
     */
    @PostMapping
    public ResponseEntity<Void> start(@PathVariable Long recordingId, @RequestParam(required = false) String kind,
                                      @RequestParam(defaultValue = "false") boolean force) {
        soundDetectionService.startDetection(recordingId, kind, force);
        return ResponseEntity.accepted().build();
    }

    /**
     * 録画の、今の版の実行記録を返す。
     *
     * @param recordingId 録画の主キー
     * @param kind        種類（{@code EAR_KISS}）
     * @return 実行記録
     * @throws IllegalArgumentException 種類が無い・不正なとき（400）
     * @throws com.example.monitor.exception.RecordingNotFoundException 録画が無いとき（404）
     * @throws com.example.monitor.exception.SoundDetectionNotFoundException 今の版で一度も検出を始めていないとき（404）
     */
    @GetMapping
    public SoundDetectionRunResponse get(@PathVariable Long recordingId, @RequestParam(required = false) String kind) {
        return soundDetectionService.getRun(recordingId, kind);
    }

    /**
     * 番号・{@code force} の変換失敗を共通の 500 処理へ渡さず、内部の型名を含めない 400 にする
     * （{@link MySoundMarkController} と同じ）。
     *
     * @param exception 番号・{@code force} の変換失敗
     * @return 入力誤りの応答
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleInvalidParameter(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "録画の番号は数で、force は true か false で指定してください"));
    }
}
