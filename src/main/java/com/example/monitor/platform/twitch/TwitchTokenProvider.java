package com.example.monitor.platform.twitch;

import com.example.monitor.config.MonitorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Twitch API のアクセストークンを取得・保持する。
 *
 * <h2>YouTube との違い</h2>
 * YouTube は API キーをクエリパラメータに付けるだけで済むが、Twitch は Client ID と
 * Client Secret から<b>有効期限付きのアクセストークンを取得する</b>方式（OAuth の
 * クライアントクレデンシャルフロー）を採る。公開情報の参照しかしないため利用者ごとの
 * 認可は不要で、アプリ単位のトークン（app access token）で足りる。
 *
 * <h2>トークンの寿命と再取得</h2>
 * 発行されるトークンの有効期間は長い（おおむね 60 日）が、無期限ではない。
 * 期限切れで監視が止まらないよう、次の 2 通りで再取得する。
 * <ul>
 *   <li>期限が近づいたら先回りして取り直す（{@link #EXPIRY_MARGIN} 分の余裕を見る）</li>
 *   <li>API 側から 401 が返ったら {@link #invalidate()} で捨てて取り直す
 *       （Twitch 側でトークンが失効させられる場合があるため、期限だけに頼らない）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TwitchTokenProvider {

    /** トークン発行のエンドポイント。 */
    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";

    /**
     * 有効期限のどれくらい手前で取り直すか。
     *
     * <p>期限ちょうどまで使うと、リクエストの往復中に切れて失敗しうる。
     * 監視は数分おきに走るため、十分な余裕を取っても再取得の回数はほとんど増えない。
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    /** トークン取得の応答待ち上限。監視ループ全体を止めないために設ける。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MonitorProperties monitorProperties;

    /** 取得済みのトークン。まだ取得していない、または捨てた場合は {@code null}。 */
    private CachedToken cachedToken;

    /**
     * 有効なアクセストークンを返す。必要なら取得し直す。
     *
     * <p>監視ループと手動チェックから呼ばれうるため同期化している。トークン取得は
     * 数百ミリ秒かかるが、期限切れ時にしか発生しないので待ち合わせの影響は小さい。
     *
     * @return アクセストークン
     * @throws IllegalStateException Client ID / Secret が未設定、または取得に失敗した場合
     */
    public synchronized String accessToken() {
        if (cachedToken != null && cachedToken.isUsableAt(Instant.now())) {
            return cachedToken.token();
        }
        cachedToken = requestNewToken();
        return cachedToken.token();
    }

    /**
     * 保持しているトークンを捨てる。次回の {@link #accessToken()} で取り直される。
     *
     * <p>API が 401 を返したときに呼ぶ。期限内でも Twitch 側で失効させられることがあるため、
     * 期限の管理だけに頼らずこの経路も用意している。
     */
    public synchronized void invalidate() {
        if (cachedToken != null) {
            log.info("Twitch のアクセストークンを破棄しました。次回の呼び出しで取り直します");
            cachedToken = null;
        }
    }

    /**
     * Twitch からアクセストークンを新規に取得する。
     *
     * @return 取得したトークン
     * @throws IllegalStateException 設定が未完了、または取得に失敗した場合
     */
    private CachedToken requestNewToken() {
        MonitorProperties.TwitchProperties twitch = monitorProperties.twitch();
        if (!twitch.isConfigured()) {
            throw new IllegalStateException(
                    "Twitch の Client ID / Client Secret が未設定です。設定画面から登録してください");
        }

        String form = "client_id=" + encode(twitch.clientId())
                + "&client_secret=" + encode(twitch.clientSecret())
                + "&grant_type=client_credentials";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // 応答本文には Client Secret は含まれないが、念のため本文全体は出さず理由だけ残す
                throw new IllegalStateException(
                        "Twitch のアクセストークンを取得できませんでした（HTTP "
                                + response.statusCode() + "）。Client ID / Secret を確認してください");
            }

            JsonNode body = objectMapper.readTree(response.body());
            String token = body.path("access_token").asText(null);
            long expiresInSeconds = body.path("expires_in").asLong(0);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Twitch の応答にアクセストークンが含まれていませんでした");
            }

            Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);
            log.info("Twitch のアクセストークンを取得しました: 有効期限={}", expiresAt);
            return new CachedToken(token, expiresAt);

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Twitch のアクセストークン取得に失敗しました: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Twitch のアクセストークン取得が中断されました");
        }
    }

    /**
     * フォーム値として安全な形に符号化する。
     *
     * @param value 符号化したい値
     * @return 符号化した文字列
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 取得済みのトークンとその有効期限。
     *
     * @param token     アクセストークン
     * @param expiresAt 有効期限
     */
    private record CachedToken(String token, Instant expiresAt) {

        /**
         * 指定時刻の時点でまだ安全に使えるかを返す。
         *
         * @param now 判定する時刻
         * @return 期限まで余裕があれば {@code true}
         */
        boolean isUsableAt(Instant now) {
            return now.isBefore(expiresAt.minus(EXPIRY_MARGIN));
        }
    }
}
