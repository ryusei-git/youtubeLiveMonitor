package com.example.monitor.controller;

import com.example.monitor.dto.AdminPasswordSetRequest;
import com.example.monitor.dto.AdminUsernameChangeRequest;
import com.example.monitor.dto.AppUserResponse;
import com.example.monitor.service.AppUserManagementService;
import jakarta.servlet.http.HttpServletRequest;
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
     * 有効化だけを受け付け、リクエスト本文による権限変更を許さない（{@link #disable} と同じ理由）。
     * @param id 利用者ID
     * @param authentication 操作者の認証情報
     * @return 成功時204
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable Long id, Authentication authentication) {
        service.enable(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * パスワードの再設定用の token を発行する。URL は画面が組み立てる。
     * @param id 利用者ID
     * @param authentication 操作者の認証情報
     * @return token と期限
     */
    @PostMapping("/{id}/password-reset")
    public AppUserManagementService.PasswordResetIssued issuePasswordReset(@PathVariable Long id,
                                                                            Authentication authentication) {
        return service.issuePasswordReset(id, authentication.getName());
    }

    /**
     * 一般利用者の利用者名を変える。操作者はリクエストの本文ではなく認証情報から取る。
     * @param id 利用者ID
     * @param body 新しい利用者名と、操作者（管理者）自身の今のパスワード
     * @param authentication 操作者の認証情報
     * @param request 操作元の IP（管理者のパスワードの照合の回数の上限に使う）
     * @return 成功時204。管理者のパスワードが違えば 403、名前が要件を満たさなければ 400、
     *         管理者・自分自身や同じ名前があれば 409、照合に続けて失敗していれば 429（{@code GlobalExceptionHandler}）
     */
    @PutMapping("/{id}/username")
    public ResponseEntity<Void> rename(@PathVariable Long id, @RequestBody AdminUsernameChangeRequest body,
                                       Authentication authentication, HttpServletRequest request) {
        service.rename(id, body.username(), body.adminPassword(), authentication.getName(), request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    /**
     * 一般利用者の新しいパスワードを決める。今のパスワードは返さない（ハッシュしか持っていない）。
     * @param id 利用者ID
     * @param body 新しいパスワードと、操作者（管理者）自身の今のパスワード
     * @param authentication 操作者の認証情報
     * @param request 操作元の IP（管理者のパスワードの照合の回数の上限に使う）
     * @return 成功時204。管理者のパスワードが違えば 403、新しいパスワードが要件を満たさなければ 400、
     *         管理者・自分自身なら 409、照合に続けて失敗していれば 429（{@code GlobalExceptionHandler}）
     */
    @PutMapping("/{id}/password")
    public ResponseEntity<Void> setPassword(@PathVariable Long id, @RequestBody AdminPasswordSetRequest body,
                                            Authentication authentication, HttpServletRequest request) {
        service.setPassword(id, body.password(), body.adminPassword(), authentication.getName(),
                request.getRemoteAddr());
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
