package com.example.monitor.dto;

/**
 * 管理者による利用者名の変更（{@code PUT /api/admin/users/{id}/username}）の本文。
 *
 * @param username      新しい利用者名
 * @param adminPassword 操作する管理者自身の今のパスワード。席を外した管理者の画面を他人に使われないための確認
 */
public record AdminUsernameChangeRequest(String username, String adminPassword) {
}
