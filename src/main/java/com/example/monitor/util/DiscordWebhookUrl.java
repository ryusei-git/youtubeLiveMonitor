package com.example.monitor.util;

import java.util.regex.Pattern;

/**
 * 利用者が登録する Discord の Webhook の URL として受け付けてよいかを判定する。
 *
 * <h2>宛先を Discord の Webhook に限る理由</h2>
 * 登録された URL へはサーバーが POST する。任意の URL を受け付けると、利用者の入力ひとつで
 * <b>サーバーから好きな宛先（同じ端末の管理画面や社内のサービスなど）へ通信させられてしまう</b>。
 * そのためホストを {@code discord.com}／{@code discordapp.com} に固定し、パスも Webhook の形
 * （{@code /api/webhooks/{ID}/{トークン}}）に限る。
 *
 * <p>クエリ・フラグメント・空白などを一切通さないほど厳しくしているのは、崩れた URL から
 * {@link java.net.URI} や HTTP の要求を組み立てると例外になり、<b>その例外のメッセージには
 * URL（＝秘密のトークン）がそのまま入る</b>ため。ログへ漏れる経路を形の段階で断つ。
 *
 * <p>登録するとき（{@code UserNotificationService}）と送る直前（{@code DiscordNotifier}）の両方で使う。
 * 送る側でも確かめるのは、DB を直接書き換えられた場合にも Discord 以外へは送らないため。
 */
public final class DiscordWebhookUrl {

    /** 保存できる長さの上限。{@code AppUser.discordWebhookUrl} の列の長さと同じ値を使う。 */
    public static final int MAX_LENGTH = 512;

    /** Discord が発行する Webhook の URL の形。ID は数字、トークンは英数字・{@code _}・{@code -}。 */
    private static final Pattern WEBHOOK_URL =
            Pattern.compile("https://(?:discord|discordapp)\\.com/api/webhooks/\\d+/[\\w-]+");

    /** ユーティリティクラスのためインスタンス化させない。 */
    private DiscordWebhookUrl() {
    }

    /**
     * Discord の Webhook の URL として受け付けてよいかを判定する。前後の空白は落としてから渡すこと。
     *
     * @param url 判定する URL
     * @return 受け付けてよければ {@code true}。{@code null} や長すぎるものは {@code false}
     */
    public static boolean isValid(String url) {
        return url != null && url.length() <= MAX_LENGTH && WEBHOOK_URL.matcher(url).matches();
    }
}
