package com.example.monitor.notification;

import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.util.DiscordWebhookUrl;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Discord の Webhook にメッセージを送信する。
 *
 * <p>このクラスは送信そのものにのみ責任を持ち、失敗時は例外を投げる。
 * 例外を結果オブジェクトに変換して監視ループを止めないようにするのは
 * {@link com.example.monitor.service.NotificationDispatcher}（全体向け）と
 * {@link com.example.monitor.service.UserNotificationService}（利用者向け）の役割。
 *
 * <p>Webhook URL が未設定でも、形が崩れていてもアプリは起動できるようにしてある（起動時に WARN を出すだけ）。
 * 設定前でもチャンネル登録などの他の機能は使えた方が都合が良いため。
 *
 * <h2>宛先が 2 種類ある</h2>
 * 全体向け（{@code .env} の {@code DISCORD_WEBHOOK_URL}）と利用者ごとの Webhook のどちらへも、
 * 共有の {@link HttpClient} で JSON を POST する（{@link #post}）。
 * 以前は全体向けだけ discord-webhooks の {@code WebhookClient} で送っていたが、次の 2 つの理由でやめた。
 * (1) 送った後に {@code join()} で待つ時間に上限が無く、429（送りすぎ）を受けるとライブラリの中で待って
 * 送り直すので、その間は巡回が止まる。(2) URL が Webhook の形でないと、作る時点で例外になって
 * アプリ全体が起動しなかった。
 * 429 は他の失敗と同じく 1 回の失敗として扱い、次の巡回で送り直す（送り直しの上限は巡回側で数える）。
 * 利用者ごとに {@code WebhookClient} を作ることもしない。1 つごとに OkHttp の接続と専用のスレッドを抱えるため
 * （宛先が利用者の数だけあり、送るたびに作ると使い捨てのスレッドが増え、持ち続けると利用者の数だけ残る）。
 * ライブラリは本文の組み立て（{@link WebhookEmbed}／{@link WebhookEmbedBuilder}）にだけ使う。
 * どちらの宛先も本文は {@link #liveStartEmbed} で組み立て、同じ見た目の通知になるようにしている。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DiscordNotifier {

    /** 通知の埋め込み左端に表示する色。配信中を示す赤。 */
    private static final int EMBED_COLOR = Color.RED.getRGB();

    /**
     * Discord の Webhook へ送るときの上限（応答本文の受け取りまで含む。
     * {@link com.example.monitor.config.HttpClientConfig} 参照）。
     *
     * <p>送信は巡回の中で順に行うため、Discord が応答しないまま待ち続けると巡回全体が止まる。
     * 接続の待ちは共有の {@link HttpClient} 側で切っているので、ここでは応答の待ちを切る。
     */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    /** 失敗の理由として Discord の応答本文を載せるときの上限の文字数。 */
    private static final int MAX_ERROR_BODY_LENGTH = 200;

    private final MonitorProperties monitorProperties;

    /** Discord の Webhook への送信（全体向け・利用者ごとの両方）に使う、アプリ全体で共有の HTTP クライアント。 */
    private final HttpClient httpClient;

    /**
     * 全体向けの Webhook URL が使えない状態なら、起動時に WARN を出す。Spring が Bean 生成後に呼び出す。
     *
     * <p>未設定でも形が崩れていても例外は投げない。設定の誤りでアプリ全体が起動しなくなるのを避けるため。
     * どちらの文言にも URL（秘密のトークンを含む）は入れない。
     */
    @PostConstruct
    void warnIfWebhookUrlUnusable() {
        String webhookUrl = monitorProperties.discord().webhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Discord の Webhook URL が未設定です。設定されるまで通知は送信されません。");
            return;
        }
        if (!DiscordWebhookUrl.isValid(webhookUrl)) {
            log.warn("DISCORD_WEBHOOK_URL が Discord の Webhook の URL の形（https://discord.com/api/webhooks/<数字>/<トークン>）"
                    + "ではありません。直すまで全体向けの通知は送信されません。");
        }
    }

    /**
     * 配信開始を知らせる埋め込みメッセージを全体向けの Webhook へ送信する。
     *
     * <p>{@link #post} が {@code ?wait=true} を付けて Discord が受理するまで待つので、
     * 送れたかどうかを呼び出し側が確実に判定できる。
     *
     * @param liveStream 通知対象の配信情報
     * @throws IllegalStateException Webhook URL が未設定・Discord の Webhook の形でない場合や、
     *                               送信できなかった場合（メッセージに URL は含めない）
     */
    public void sendLiveStartNotification(LiveStreamDetails liveStream) {
        String webhookUrl = monitorProperties.discord().webhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException("Discord の Webhook URL が未設定です（環境変数 DISCORD_WEBHOOK_URL を設定してください）");
        }
        post(webhookUrl, liveStartEmbed(liveStream));
    }

    /**
     * 配信開始を知らせる埋め込みメッセージを、指定した Webhook へ送信する。利用者ごとの通知に使う。
     *
     * @param webhookUrl 送り先の Webhook の URL
     * @param liveStream 通知対象の配信情報
     * @throws IllegalStateException 送信できなかった場合。メッセージに URL は含めない
     */
    public void sendLiveStartNotification(String webhookUrl, LiveStreamDetails liveStream) {
        post(webhookUrl, liveStartEmbed(liveStream));
    }

    /**
     * 登録した Webhook に届くかを確かめるためのテストの通知を送る。
     *
     * @param webhookUrl 送り先の Webhook の URL
     * @throws IllegalStateException 送信できなかった場合。メッセージに URL は含めない
     */
    public void sendTestNotification(String webhookUrl) {
        post(webhookUrl, new WebhookEmbedBuilder()
                .setColor(EMBED_COLOR)
                .setTitle(new WebhookEmbed.EmbedTitle("テスト通知", null))
                .setDescription("YouTube Live Monitor からのテスト通知です。"
                        + "購読しているチャンネルの配信が始まると、ここに通知が届きます。")
                .build());
    }

    /**
     * 管理者向けの知らせを、全体向けの Webhook（{@code DISCORD_WEBHOOK_URL}）へ送る。
     *
     * <p>呼び出し元は録画の開始判断の途中にあり、Discord の応答待ちで巡回を止めたくないので、
     * 上限（{@link #SEND_TIMEOUT}）つきの {@link #post} で送る。
     *
     * @param message 知らせる本文
     * @throws IllegalStateException Webhook URL が未設定・不正な場合や、送信できなかった場合
     */
    public void sendAdminAlert(String message) {
        post(monitorProperties.discord().webhookUrl(), new WebhookEmbedBuilder()
                .setColor(EMBED_COLOR)
                .setTitle(new WebhookEmbed.EmbedTitle("管理者への通知", null))
                .setDescription(message)
                .build());
    }

    /**
     * 配信開始の通知の本文を組み立てる。全体向けと利用者向けで同じものを使う。
     *
     * @param liveStream 通知対象の配信情報
     * @return 埋め込みメッセージ
     */
    private WebhookEmbed liveStartEmbed(LiveStreamDetails liveStream) {
        WebhookEmbedBuilder embedBuilder = new WebhookEmbedBuilder()
                .setColor(EMBED_COLOR)
                .setTitle(new WebhookEmbed.EmbedTitle(liveStream.getTitle(), liveStream.getWatchUrl()))
                .setAuthor(new WebhookEmbed.EmbedAuthor(liveStream.getChannelTitle(), null, null))
                .setDescription("🔴 配信が開始されました");

        if (liveStream.getThumbnailUrl() != null) {
            embedBuilder.setImageUrl(liveStream.getThumbnailUrl());
        }
        return embedBuilder.build();
    }

    /**
     * 埋め込みメッセージを 1 つ、指定した Webhook へ POST する。
     *
     * <p>本文の JSON は {@link WebhookEmbed} 自身の変換で作る。
     * {@code wait=true} を付けるのは、Discord が受理したかを応答で確実に判定するため。
     *
     * <p>失敗のメッセージには URL を入れない（呼び出し側がログや API の応答に使うため）。
     * 形を先に確かめるのも、崩れた URL で要求を組み立てたときの例外に URL が入るのを防ぐため
     * （{@link DiscordWebhookUrl} 参照）。
     *
     * @param webhookUrl 送り先の Webhook の URL
     * @param embed      送る埋め込みメッセージ
     * @throws IllegalStateException 送信できなかった場合
     */
    private void post(String webhookUrl, WebhookEmbed embed) {
        if (!DiscordWebhookUrl.isValid(webhookUrl)) {
            throw new IllegalStateException("Discord の Webhook の URL ではないため送信しません");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl + "?wait=true"))
                .timeout(SEND_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        new JSONObject().put("embeds", new JSONArray().put(embed)).toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Discord へ送信できませんでした: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Discord への送信が中断されました");
        }

        if (response.statusCode() / 100 != 2) {
            // 応答本文は Discord の説明（"Unknown Webhook" など）で、利用者が原因を知る手がかりになる
            String body = response.body() == null ? "" : response.body();
            throw new IllegalStateException("Discord が HTTP " + response.statusCode() + " を返しました: "
                    + body.substring(0, Math.min(body.length(), MAX_ERROR_BODY_LENGTH)));
        }
    }
}
