package com.example.monitor.dto;

import com.example.monitor.entity.AppUser;
import java.time.LocalDateTime;

/**
 * パスワードハッシュを管理画面にも公開しないため、表示可能な項目だけを持つ。
 * @param id 利用者ID
 * @param username 利用者名
 * @param role 権限（表示専用）
 * @param enabled 有効か
 * @param createdAt 登録日時
 * @param lastLoginAt 最終ログイン日時。未ログインならnull
 */
public record AppUserResponse(Long id, String username, AppUser.Role role, boolean enabled,
                              LocalDateTime createdAt, LocalDateTime lastLoginAt) {
    /**
     * エンティティへの参照をレスポンスに残さず、秘密情報の混入を防ぐ。
     * @param user 保存済み利用者
     * @return 公開可能な項目
     */
    public static AppUserResponse from(AppUser user) {
        return new AppUserResponse(user.getId(), user.getUsername(), user.getRole(),
                user.isEnabled(), user.getCreatedAt(), user.getLastLoginAt());
    }
}
