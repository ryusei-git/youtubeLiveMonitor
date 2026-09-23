package com.example.monitor.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 録画ファイルを {@code /recordings/**} で配信するための設定。
 *
 * <p>ブラウザの {@code <video>} タグでシークバー操作をするには HTTP の Range リクエスト
 * （部分取得、206 応答）への対応が要る。自前でストリーミング処理を書く代わりに、
 * Spring の静的リソース配信機構（{@link ResourceHandlerRegistry}）に任せている。
 * こちらは Range リクエストへの対応やパストラバーサル対策が最初から組み込まれている。
 *
 * <p>このリソースハンドラ自体はパスに対するアクセス制御を持たない。
 * {@code /recordings/**} を認証済み利用者に限定しているのは
 * {@code com.example.monitor.security.SecurityConfig} 側であり、Spring Security の
 * フィルターチェーンは本クラスの静的リソース配信より前段で動作するため、未認証の
 * リクエストはここに到達する前に弾かれる。ここを素通しにすると URL
 * （チャンネルIDと動画ID）さえ分かれば誰でも録画ファイルを取得できてしまうため、
 * 認証を追加した際に真っ先に確認した箇所（設計書 3.4 参照）。
 */
@Configuration
@RequiredArgsConstructor
public class RecordingResourceConfig implements WebMvcConfigurer {

    private final MonitorProperties monitorProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String directory = monitorProperties.recording().directory();
        registry.addResourceHandler("/recordings/**")
                .addResourceLocations("file:" + directory + "/");
    }
}
