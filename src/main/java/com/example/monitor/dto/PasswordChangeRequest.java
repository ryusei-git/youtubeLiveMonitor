package com.example.monitor.dto;

/**
 * 自分のパスワードの変更（{@code PUT /api/my/password}）の本文。
 *
 * @param currentPassword 今のパスワード。本人であることの確認に使う
 * @param newPassword     新しいパスワード
 */
public record PasswordChangeRequest(String currentPassword, String newPassword) {
}
