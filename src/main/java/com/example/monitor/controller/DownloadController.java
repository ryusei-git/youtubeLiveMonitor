package com.example.monitor.controller;

import com.example.monitor.dto.DownloadRequest;
import com.example.monitor.dto.DownloadResponse;
import com.example.monitor.service.VideoDownloadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * URL を指定した動画のダウンロードを受け付ける REST API。
 *
 * <p>結果（成否・保存先）は録画履歴に入るため、参照は {@link RecordingController} 側で行う。
 * ダウンロードした動画は録画一覧にそのまま並び、同じ画面から再生・削除できる。
 *
 * <p>返すのは 202 Accepted。<b>ダウンロードが終わったのではなく、受け付けたことを表す</b>ため
 * （完了を待つと数十分返らない応答になる）。
 */
@RestController
@RequestMapping("/api/downloads")
@RequiredArgsConstructor
public class DownloadController {

    private final VideoDownloadService videoDownloadService;

    /**
     * 動画のダウンロードを開始する。
     *
     * @param request ダウンロードしたい動画の URL
     * @return 受け付けた内容（HTTP 202）
     * @throws IllegalArgumentException 対応していない URL、または動画の情報を取得できない場合（400）
     * @throws com.example.monitor.exception.VideoAlreadyDownloadedException
     *         既に同じ動画の録画履歴がある場合（409）
     */
    @PostMapping
    public ResponseEntity<DownloadResponse> startDownload(@Valid @RequestBody DownloadRequest request) {
        DownloadResponse accepted = videoDownloadService.startDownload(request.url());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }
}
