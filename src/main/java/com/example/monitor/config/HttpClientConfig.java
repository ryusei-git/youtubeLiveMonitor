package com.example.monitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * アプリ全体で共有する {@link HttpClient} を組み立てる。
 *
 * <p>{@link com.example.monitor.service.LiveStreamDetector} がフィールド初期化子で
 * 直接 {@code new} していたものを Bean 化した。理由はテスト容易性のため。
 * フィールド初期化子のままだとテスト時にモックへ差し替えられず、
 * 実際に YouTube へ通信しないと {@code findLiveVideoId} の分岐を検証できなかった。
 */
@Configuration
public class HttpClientConfig {

    /** 接続タイムアウト。1 チャンネルへの接続待ちで監視ループ全体が止まらないようにする。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * アプリ全体で使い回す {@link HttpClient} を生成する。
     *
     * @return 設定済みの {@link HttpClient}
     */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
