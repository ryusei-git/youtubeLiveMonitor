package com.example.monitor.platform.twitch;

/**
 * Twitch Helix API の {@code /helix/users} が返すユーザー 1 件分。
 *
 * <p>チャンネル登録時に、利用者が入力したログイン名から不変の ID を得るために使う。
 *
 * @param id              ユーザー ID（数値文字列）。<b>変更されない</b>ため監視の識別子にはこちらを使う
 * @param login           ログイン名。URL に現れる名前で、利用者が変更できる
 * @param displayName     表示名
 * @param profileImageUrl プロフィール画像の URL
 */
public record TwitchUser(
        String id,
        String login,
        String displayName,
        String profileImageUrl
) {}
