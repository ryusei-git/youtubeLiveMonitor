package com.example.monitor.controller;
import com.example.monitor.service.OrphanedPreviewService;
import com.example.monitor.dto.OrphanedCleanupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 録画履歴に無い動画のファイル（孤立ファイル）の削除を、確認と確定の 2 段階で受け付ける API。
 *
 * <p>削除の確定前に対象と容量を見せるための専用経路。プレビューで返したトークンを確定時に
 * 送り返させることで、利用者が見た一覧と実際に消す一覧が食い違わないようにしている。
 */
@RestController
@RequestMapping("/api/recordings/orphaned")
@RequiredArgsConstructor
public class OrphanedPreviewController {
    private final OrphanedPreviewService service;

    /**
     * 削除候補と除外理由を、ファイルを変えずに返す。
     *
     * <p>録画中のファイルは候補に入れず、除外理由として返す。
     *
     * @return 削除候補・除外理由・合計サイズと、確定時に送り返すトークン
     */
    @GetMapping("/preview")
    public OrphanedPreviewService.Preview preview() {
        return service.preview();
    }

    /**
     * プレビューで確認したファイルだけを削除する。
     *
     * <p>トークンが今の削除候補と合わなければ（確認後にファイルが増減・変化していれば）何も消さずに
     * 400 Bad Request を返す。確認していないファイルを巻き込まないため。
     *
     * @param token プレビューで受け取ったトークン
     * @return 削除の集計（チャンネル数・ファイル数・解放した容量・消さなかったファイルと理由）
     */
    @DeleteMapping("/confirmed")
    public OrphanedCleanupResponse delete(@RequestParam String token) {
        return service.deleteConfirmed(token);
    }
}
