package com.example.monitor.config;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * YouTube Data API のクライアントを組み立てる設定。
 *
 * <p>API キーは各リクエストのクエリパラメータとして付与する。OAuth を使っていないのは、
 * 公開情報の参照しか行わないため。利用者ごとの認可が不要な範囲に機能を限定している。
 */
@Configuration
@RequiredArgsConstructor
public class YouTubeApiConfig {

    /** API のリクエストに付けるアプリケーション名。Google 側のログに記録される。 */
    private static final String APPLICATION_NAME = "youtube-live-monitor";

    /** API キーを渡すためのクエリパラメータ名。 */
    private static final String API_KEY_PARAMETER = "key";

    private final MonitorProperties monitorProperties;

    /**
     * YouTube Data API のクライアントを生成する。
     *
     * <p>JSON の解析には {@link GsonFactory} を使う。以前使っていた {@code JacksonFactory} は
     * 非推奨になっているため。
     *
     * @return 設定済みの API クライアント
     */
    @Bean
    public YouTube youtube() {
        String apiKey = monitorProperties.youtube().apiKey();

        return new YouTube.Builder(
                new NetHttpTransport(),
                new GsonFactory(),
                request -> request.getUrl().put(API_KEY_PARAMETER, apiKey))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
