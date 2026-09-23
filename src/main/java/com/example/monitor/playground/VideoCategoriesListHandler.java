package com.example.monitor.playground;

import com.google.api.services.youtube.YouTube;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code videoCategories.list} のお試し実行。動画カテゴリの一覧を取得する。
 *
 * <p>入力が地域コードだけで済むため、<b>APIキーが正しく設定されているかの疎通確認</b>に向く。
 * 動画IDやチャンネルIDを用意しなくても叩けるので、まずここから試すとよい。
 */
@Component
@RequiredArgsConstructor
public class VideoCategoriesListHandler implements PlaygroundApiHandler {

    /** 何も入力しなかった場合に取得する part。 */
    private static final String DEFAULT_PARTS = "snippet";

    /** 既定の地域コード。 */
    private static final String DEFAULT_REGION_CODE = "JP";

    private final YouTube youtube;

    @Override
    public ApiDefinition definition() {
        return new ApiDefinition(
                "videoCategories.list",
                "動画カテゴリ一覧",
                "地域ごとの動画カテゴリを一覧します。入力が地域コードだけで済むので、"
                        + "APIキーが正しく設定されているかの疎通確認に使えます。",
                1,
                List.of(
                        ApiParameter.optional("regionCode", "地域コード", DEFAULT_REGION_CODE),
                        ApiParameter.optional("part", "取得する part", DEFAULT_PARTS)));
    }

    @Override
    public Object execute(Map<String, String> parameters) throws IOException {
        return youtube.videoCategories()
                .list(PlaygroundParameters.list(parameters, "part", DEFAULT_PARTS))
                .setRegionCode(PlaygroundParameters.text(parameters, "regionCode", DEFAULT_REGION_CODE))
                .execute();
    }
}
