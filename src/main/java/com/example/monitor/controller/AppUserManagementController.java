package com.example.monitor.controller;

import com.example.monitor.dto.AppUserResponse;
import com.example.monitor.service.AppUserManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

/** 管理者専用の名前空間に閉じ、任意の属性や権限を書き換えるAPIを提供しない。 */
@RestController
@Profile("!cli")
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AppUserManagementController {
    private final AppUserManagementService service;

    /** @return 秘密情報を含まない利用者一覧 */
    @GetMapping
    public List<AppUserResponse> list() {
        return service.list();
    }

    /**
     * 無効化だけを受け付け、リクエスト本文による権限変更を許さない。
     * @param id 利用者ID
     * @param authentication 操作者の認証情報
     * @return 成功時204
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable Long id, Authentication authentication) {
        service.disable(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * 操作者をリクエストの入力値に任せない。
     * @param id 利用者ID
     * @param authentication 操作者の認証情報
     * @return 成功時204
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        service.delete(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * 共通の例外処理で404・409が500に変わらないよう、このAPIの期待する失敗を変換する。
     * @param exception 操作を拒否した理由
     * @return 画面に表示できる理由とステータス
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("error", exception.getReason() == null ? "操作できません" : exception.getReason()));
    }
}
