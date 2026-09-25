package com.example.monitor.controller;

import com.example.monitor.dto.InvitationCheckResponse;
import com.example.monitor.dto.RegistrationRequest;
import com.example.monitor.service.InvitationService;
import jakarta.validation.Valid;
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
 * 招待リンクから利用者が自分で登録する API。
 *
 * <h2>ここだけ未ログインで叩ける</h2>
 * 登録する時点では、まだアカウントが無いので当然認証は通らない。
 * <b>代わりに招待の token が鍵になる</b>——token は推測できない長さの乱数で、
 * 1 回使えば無効、かつ期限付き（{@link InvitationService} 参照）。
 *
 * <p><b>権限はリクエストで選べない。</b>この経路で作られるのは常に一般利用者で、
 * 管理者を増やすことはできない。
 *
 * <h2>{@code @Profile("!cli")} を付けている理由</h2>
 * 依存する InvitationService が {@code !cli} のため、こちらにも付ける。
 */
@Profile("!cli")
@RestController
@RequestMapping("/api/registration")
@RequiredArgsConstructor
public class RegistrationController {

    private final InvitationService invitationService;

    /**
     * 招待リンクが今使えるかを返す。登録画面を開いた時点の表示に使う。
     *
     * @param token 招待リンクに載っていた文字列
     * @return 確認結果
     */
    @GetMapping
    public InvitationCheckResponse check(@RequestParam String token) {
        return invitationService.check(token);
    }

    /**
     * 招待を使って利用者を登録する。
     *
     * @param request token・利用者名・パスワード
     * @return 登録できたら 204
     */
    @PostMapping
    public ResponseEntity<Void> register(@Valid @RequestBody RegistrationRequest request) {
        invitationService.register(request.token(), request.username(), request.password());
        return ResponseEntity.noContent().build();
    }
}
