package com.example.monitor.controller;

import com.example.monitor.dto.MyUploadListResponse;
import com.example.monitor.dto.MyUploadResponse;
import com.example.monitor.service.VideoUploadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * ログイン中の利用者が手元の動画を上げる API。
 *
 * <p><b>マルチパートを使わず、本文をそのまま受ける {@code PUT} にしている。</b>マルチパートの一時ファイルは
 * {@code /tmp} に置かれ、本番の {@code /tmp} は tmpfs（メモリ）なので、大きな動画で {@code mem_limit} を
 * 食い潰す。本文を読みながら録画フォルダへ書く処理は {@link VideoUploadService} にある。
 * 本文を読む経路を増やさないこと（{@code docs/pitfalls.md}「大きな本文を受ける API は、本文を先に読むものを
 * 止めておく」）。
 *
 * <p>上げた動画は録画と同じ行になるので、一覧と再生は {@link MyRecordingController} をそのまま使う。
 * ここにあるのは、上げる・自分の分を見る・自分の分を消す、だけ。対象の利用者はパスから指定できず、
 * 常にログイン中の本人になる。
 *
 * <p>Web でしか使わないため {@code @Profile("!cli")} を付けている（{@code docs/pitfalls.md} 参照）。
 */
@RestController
@RequestMapping("/api/my/uploads")
@RequiredArgsConstructor
@Profile("!cli")
public class MyUploadController {

    private final VideoUploadService videoUploadService;

    /**
     * 動画を受け取って取り込む。取り込み終わるまで応答しない。
     *
     * @param fileName       元のファイル名（題名を省いたときに使う）
     * @param title          題名（200 字まで）。省略・空白なら {@code fileName} から拡張子を除いた値
     * @param request        本文（動画のバイト列）と {@code Content-Length} を読むための要求
     * @param authentication ログイン中の利用者
     * @return 取り込んだ動画（HTTP 201）
     * @throws IllegalArgumentException 題名・長さが不正な場合、取り込めない動画の場合（400）
     * @throws com.example.monitor.exception.UploadTooLargeException 1 本の上限を超える場合（413）
     * @throws com.example.monitor.exception.UploadRejectedException
     *         空き容量・利用者の容量・同時に上げられる本数の都合で受け付けられない場合（409）
     * @throws IOException 本文を読めない（接続が切れた）場合、ファイルを書けない場合
     */
    @PutMapping
    public ResponseEntity<MyUploadResponse> upload(@RequestParam(required = false) String fileName,
                                                   @RequestParam(required = false) String title,
                                                   HttpServletRequest request,
                                                   Authentication authentication) throws IOException {
        MyUploadResponse uploaded = videoUploadService.upload(authentication.getName(), fileName, title,
                request.getContentLengthLong(), request.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED).body(uploaded);
    }

    /**
     * 自分が上げた動画を新しい順に返す。上限と使っている量も一緒に返す。
     *
     * @param authentication ログイン中の利用者
     * @return 上限と上げた動画
     */
    @GetMapping
    public MyUploadListResponse list(Authentication authentication) {
        return videoUploadService.list(authentication.getName());
    }

    /**
     * 自分が上げた動画を消す。
     *
     * @param id             録画の主キー
     * @param authentication ログイン中の利用者
     * @return 本文なしの 204
     * @throws com.example.monitor.exception.RecordingNotFoundException 無い場合、ほかの人の動画の場合（404）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        videoUploadService.delete(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
