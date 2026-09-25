package com.example.monitor.controller;

import com.example.monitor.dto.DownloadRequest;
import com.example.monitor.dto.DownloadResponse;
import com.example.monitor.service.VideoDownloadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ログイン中の利用者が URL を渡して動画を保存する API。
 *
 * <h2>サービスに保存</h2>
 * 中身は管理者の手動ダウンロード（{@link DownloadController}）と同じで、サービスの録画として残る。
 * {@code /api/downloads/**} は ADMIN のみなので、認証だけを求める {@code /api/my/} の下に口を分けている。
 * 配信中・待機所の拒否、取り直さない判定、空き容量の判定、監査ログ（操作者はログイン中の本人）は
 * すべて {@link VideoDownloadService#startDownload(String)} 側にあり、管理者と利用者で食い違わない。
 *
 * <p>Web でしか使わないため {@code @Profile("!cli")} を付けている（{@code docs/pitfalls.md} 参照）。
 */
@RestController
@RequestMapping("/api/my/downloads")
@RequiredArgsConstructor
@Profile("!cli")
public class MyDownloadController {

    private final VideoDownloadService videoDownloadService;

    /**
     * 動画をサービスの録画として保存し始める。
     *
     * <p>返すのは 202 Accepted（受け付けただけで、終わってはいない。{@link DownloadController} と同じ）。
     *
     * @param request 保存したい動画の URL
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
