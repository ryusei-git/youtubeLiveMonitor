package com.example.monitor.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 招待リンクから利用者を登録するときのリクエスト。
 *
 * <p><b>権限（{@code role}）は受け取らない。</b>受け取る形にすると、
 * リクエストを書き換えるだけで管理者アカウントを作れてしまう。
 * この経路で作られるのは常に一般利用者（{@code USER}）に固定している。
 *
 * @param token    招待リンクに載っていた文字列
 * @param username 希望する利用者名
 * @param password 本人が決めるパスワード。管理者も知らない
 */
public record RegistrationRequest(
        @NotBlank String token,
        @NotBlank String username,
        @NotBlank String password
) {
}
