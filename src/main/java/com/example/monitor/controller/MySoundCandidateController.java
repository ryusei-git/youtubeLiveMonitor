package com.example.monitor.controller;

import com.example.monitor.dto.SoundCandidateListResponse;
import com.example.monitor.dto.SoundCandidateResponse;
import com.example.monitor.dto.SoundCandidateVerdictRequest;
import com.example.monitor.service.SoundDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 検出器が録画に付けた候補（耳キスなど）を見て、「耳キス／ちがう」を答える API。利用者の再生画面から使う（Issue #470）。
 *
 * <p>人の印（{@link MySoundMarkController}）と別のクラスにしている。候補は別のテーブルで、操作も
 * 「付ける・消す」ではなく「答える」と違うため。{@code /api/my/**} はログインしていれば使える（{@code SecurityConfig}）。
 *
 * <p>依存する {@link SoundDetectionService} はプロファイルを問わず作られるので、{@code @Profile("!cli")} は
 * 付けていない（{@link MySoundMarkController} と同じ）。
 */
@RestController
@RequestMapping("/api/my/recordings/{recordingId}/sound-candidates")
@RequiredArgsConstructor
public class MySoundCandidateController {

    private final SoundDetectionService soundDetectionService;

    /**
     * 録画に付いた、今の版の候補を位置の順に返す。今の版の検出が済んだか（{@code state}）も一緒に返す。
     *
     * <p>{@code kind} を省略可にしているのは、省略されたときに枠組みの 500 ではなく
     * 種類が不正の 400 を返すため（サービスが確かめる）。
     *
     * @param recordingId 録画の主キー
     * @param kind        種類（{@code EAR_KISS}）
     * @return 状態・今の版・候補
     * @throws IllegalArgumentException 種類が無い・不正なとき（400）
     * @throws com.example.monitor.exception.RecordingNotFoundException 録画が無いとき（404）
     */
    @GetMapping
    public SoundCandidateListResponse list(@PathVariable Long recordingId, @RequestParam(required = false) String kind) {
        return soundDetectionService.listCandidates(recordingId, kind);
    }

    /**
     * 候補に答える。{@code verdict} が {@code null} なら取り消す。
     *
     * @param recordingId 録画の主キー
     * @param candidateId 候補の主キー
     * @param request     答え
     * @return 答えた後の候補
     * @throws IllegalArgumentException 答えの値が不正なとき（400）
     * @throws com.example.monitor.exception.RecordingNotFoundException 録画が無いとき（404）
     * @throws com.example.monitor.exception.SoundDetectionNotFoundException
     *         候補が無い・別の録画の候補・今の版でない候補のとき（404）
     */
    @PutMapping("/{candidateId}/verdict")
    public SoundCandidateResponse answer(@PathVariable Long recordingId, @PathVariable Long candidateId,
                                         @RequestBody SoundCandidateVerdictRequest request) {
        return soundDetectionService.answer(recordingId, candidateId, request);
    }

    /**
     * パスの番号の変換失敗を共通の 500 処理へ渡さず、内部の型名を含めない 400 にする
     * （{@link MySoundMarkController} と同じ）。
     *
     * @param exception パスの番号の変換失敗
     * @return 入力誤りの応答
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPath(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "録画・候補の番号は数で指定してください"));
    }
}
