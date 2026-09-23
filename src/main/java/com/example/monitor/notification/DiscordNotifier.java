package com.example.monitor.notification;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.WebhookClientBuilder;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.LiveStreamDetails;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.Color;

/**
 * Discord の Webhook にメッセージを送信する。
 *
 * <p>このクラスは送信そのものにのみ責任を持ち、失敗時は例外を投げる。
 * 例外を結果オブジェクトに変換して監視ループを止めないようにするのは
 * {@link com.example.monitor.service.NotificationDispatcher} の役割。
 *
 * <p>Webhook URL が未設定でもアプリは起動できるようにしてある（起動時に WARN を出すだけ）。
 * 設定前でもチャンネル登録などの他の機能は使えた方が都合が良いため。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DiscordNotifier {

    /** 通知の埋め込み左端に表示する色。配信中を示す赤。 */
    private static final int EMBED_COLOR = Color.RED.getRGB();

    private final MonitorProperties monitorProperties;

    /** Webhook URL が未設定の場合は {@code null} のままとなり、送信時に例外を投げる。 */
    private WebhookClient webhookClient;

    /**
     * Webhook クライアントを初期化する。Spring が Bean 生成後に呼び出す。
     *
     * <p>URL が未設定でも例外は投げない。設定漏れでアプリ全体が起動しなくなるのを避けるため。
     */
    @PostConstruct
    void initializeWebhookClient() {
        String webhookUrl = monitorProperties.discord().webhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Discord の Webhook URL が未設定です。設定されるまで通知は送信されません。");
            return;
        }
        this.webhookClient = new WebhookClientBuilder(webhookUrl)
                .setWait(true)
                .build();
    }

    /**
     * 配信開始を知らせる埋め込みメッセージを送信する。
     *
     * <p>{@code setWait(true)} で構築したクライアントを使い、
     * Discord 側が受理するまで待ってから復帰する。送信できたかどうかを
     * 呼び出し側が確実に判定できるようにするため。
     *
     * @param liveStream 通知対象の配信情報
     * @throws IllegalStateException Webhook URL が未設定の場合
     */
    public void sendLiveStartNotification(LiveStreamDetails liveStream) {
        if (webhookClient == null) {
            throw new IllegalStateException("Discord の Webhook URL が未設定です（環境変数 DISCORD_WEBHOOK_URL を設定してください）");
        }

        WebhookEmbedBuilder embedBuilder = new WebhookEmbedBuilder()
                .setColor(EMBED_COLOR)
                .setTitle(new WebhookEmbed.EmbedTitle(liveStream.getTitle(), liveStream.getWatchUrl()))
                .setAuthor(new WebhookEmbed.EmbedAuthor(liveStream.getChannelTitle(), null, null))
                .setDescription("🔴 配信が開始されました");

        if (liveStream.getThumbnailUrl() != null) {
            embedBuilder.setImageUrl(liveStream.getThumbnailUrl());
        }

        webhookClient.send(embedBuilder.build()).join();
    }

    /** アプリ終了時に Webhook クライアントの内部スレッドを解放する。Spring が Bean 破棄前に呼び出す。 */
    @PreDestroy
    void closeWebhookClient() {
        if (webhookClient != null) {
            webhookClient.close();
        }
    }
}
