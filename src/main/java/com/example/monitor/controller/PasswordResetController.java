package com.example.monitor.controller;

import com.example.monitor.dto.InvitationCheckResponse;
import com.example.monitor.service.PasswordChangeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理者が発行した再設定用のリンクから、パスワードを決め直す API（#324）。
 *
 * <p>パスワードを忘れてログインできない人が叩くので、{@link RegistrationController} と同じく未ログインで通す
 * （{@code SecurityConfig}）。<b>代わりに再設定用の token が鍵になる</b>——推測できない乱数・1 回限り・24 時間。
 * 使えないリンク・短いパスワードは {@link IllegalArgumentException} として 400 になる（{@code GlobalExceptionHandler}）。
 *
 * <p>{@link PasswordChangeService} が cli プロファイルで作られないため {@code @Profile("!cli")}。
 */
@Profile("!cli")
@RestController
@RequestMapping("/api/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordChangeService passwordChangeService;

    /**
     * 再設定用のリンクが今使えるかを返す。再設定の画面を開いた時点の表示に使う。
     *
     * @param token 再設定用のリンクに載っていた文字列
     * @return 確認結果
     */
    @GetMapping
    public InvitationCheckResponse check(@RequestParam String token) {
        return passwordChangeService.checkResetToken(token);
    }

    /**
     * 再設定用のリンクからパスワードを決め直す。
     *
     * @param body    token と新しいパスワード
     * @param request 操作元の IP を監査ログに残すため
     * @return 204
     */
    @PostMapping
    public ResponseEntity<Void> reset(@RequestBody PasswordResetRequest body, HttpServletRequest request) {
        passwordChangeService.resetPassword(body.token(), body.password(), request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    /**
     * 再設定のリクエスト。対象の利用者は受け取らない（token から決まる。受け取ると他人のパスワードを変えられる）。
     *
     * @param token    再設定用のリンクに載っていた文字列
     * @param password 本人が決める新しいパスワード。管理者も知らない
     */
    public record PasswordResetRequest(String token, String password) {
    }
}
