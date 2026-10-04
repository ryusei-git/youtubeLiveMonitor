package com.example.monitor.dto;

/**
 * 管理者による一般利用者のパスワードの設定（{@code PUT /api/admin/users/{id}/password}）の本文。
 *
 * @param password      対象の利用者の新しいパスワード
 * @param adminPassword 操作する管理者自身の今のパスワード。席を外した管理者の画面を他人に使われないための確認
 */
public record AdminPasswordSetRequest(String password, String adminPassword) {
}
