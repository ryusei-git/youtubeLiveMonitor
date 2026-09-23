package com.example.monitor.playground;

import com.google.api.services.youtube.YouTube;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code videos.list} のお試し実行。動画 ID を指定して動画の詳細を取得する。
 *
 * <p>監視機能が配信検知後に叩いているのと同じ API。{@code part} に
 * {@code liveStreamingDetails} を入れると、配信の開始・終了時刻や同時視聴者数まで取れる。
 */
@Component
@RequiredArgsConstructor
public class VideosListHandler implements PlaygroundApiHandler {

    /** 何も入力しなかった場合に取得する part。まず何が返るか見たいので広めに指定する。 */
    private static final String DEFAULT_PARTS = "snippet,liveStreamingDetails,statistics,contentDetails";

    private final YouTube youtube;

    @Override
    public ApiDefinition definition() {
        return new ApiDefinition(
                "videos.list",
                "動画の詳細",
                "動画IDから、タイトル・投稿日時・再生数・配信情報などを取得します。"
                        + "配信中の動画なら liveStreamingDetails に同時視聴者数が入ります。",
                1,
                List.of(
                        ApiParameter.required("id", "動画ID", "VAv4HcQPAQk（カンマ区切りで複数可）"),
                        ApiParameter.optional("part", "取得する part", DEFAULT_PARTS)));
    }

    @Override
    public Object execute(Map<String, String> parameters) throws IOException {
        return youtube.videos()
                .list(PlaygroundParameters.list(parameters, "part", DEFAULT_PARTS))
                .setId(PlaygroundParameters.list(parameters, "id", ""))
                .execute();
    }
}
