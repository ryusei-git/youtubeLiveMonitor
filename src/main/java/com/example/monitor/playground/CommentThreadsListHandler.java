package com.example.monitor.playground;

import com.google.api.services.youtube.YouTube;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code commentThreads.list} のお試し実行。動画に付いたコメントを取得する。
 *
 * <p>コメントが無効化されている動画では 403 が返る。これは不具合ではなく
 * その動画の設定なので、エラー内容をそのまま表示して判別できるようにしている。
 */
@Component
@RequiredArgsConstructor
public class CommentThreadsListHandler implements PlaygroundApiHandler {

    /** 何も入力しなかった場合に取得する part。 */
    private static final String DEFAULT_PARTS = "snippet";

    /** 1回あたりの既定取得件数。上限は 100。 */
    private static final long DEFAULT_MAX_RESULTS = 10L;

    private final YouTube youtube;

    @Override
    public ApiDefinition definition() {
        return new ApiDefinition(
                "commentThreads.list",
                "コメント",
                "動画IDから、付いているコメントを取得します。"
                        + "コメントが無効な動画では 403 が返ります（設定によるもので、不具合ではありません）。",
                1,
                List.of(
                        ApiParameter.required("videoId", "動画ID", "VAv4HcQPAQk"),
                        ApiParameter.optional("order", "並び順", "time / relevance"),
                        ApiParameter.optional("maxResults", "取得件数（最大100）", String.valueOf(DEFAULT_MAX_RESULTS)),
                        ApiParameter.optional("part", "取得する part", DEFAULT_PARTS)));
    }

    @Override
    public Object execute(Map<String, String> parameters) throws IOException {
        YouTube.CommentThreads.List request = youtube.commentThreads()
                .list(PlaygroundParameters.list(parameters, "part", DEFAULT_PARTS))
                .setVideoId(PlaygroundParameters.text(parameters, "videoId", ""))
                .setMaxResults(PlaygroundParameters.count(parameters, "maxResults", DEFAULT_MAX_RESULTS));

        String order = PlaygroundParameters.text(parameters, "order", "");
        if (!order.isEmpty()) {
            request.setOrder(order);
        }
        return request.execute();
    }
}
