package com.example.monitor.playground;

import com.google.api.services.youtube.YouTube;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code playlists.list} のお試し実行。チャンネルが公開している再生リストを一覧する。
 *
 * <p>ここで得た再生リストIDを {@code playlistItems.list} に渡すと中身をたどれる。
 */
@Component
@RequiredArgsConstructor
public class PlaylistsListHandler implements PlaygroundApiHandler {

    /** 何も入力しなかった場合に取得する part。 */
    private static final String DEFAULT_PARTS = "snippet,contentDetails";

    /** 1回あたりの既定取得件数。上限は 50。 */
    private static final long DEFAULT_MAX_RESULTS = 25L;

    private final YouTube youtube;

    @Override
    public ApiDefinition definition() {
        return new ApiDefinition(
                "playlists.list",
                "チャンネルの再生リスト一覧",
                "チャンネルIDから、そのチャンネルが公開している再生リストを一覧します。"
                        + "非公開の再生リストは取得できません（OAuth が必要なため）。",
                1,
                List.of(
                        ApiParameter.required("channelId", "チャンネルID", "UCmzXkdzeCaAfXs2mD0JeMZA"),
                        ApiParameter.optional("maxResults", "取得件数（最大50）", String.valueOf(DEFAULT_MAX_RESULTS)),
                        ApiParameter.optional("pageToken", "続きを読む場合のトークン", "前回の nextPageToken"),
                        ApiParameter.optional("part", "取得する part", DEFAULT_PARTS)));
    }

    @Override
    public Object execute(Map<String, String> parameters) throws IOException {
        YouTube.Playlists.List request = youtube.playlists()
                .list(PlaygroundParameters.list(parameters, "part", DEFAULT_PARTS))
                .setChannelId(PlaygroundParameters.text(parameters, "channelId", ""))
                .setMaxResults(PlaygroundParameters.count(parameters, "maxResults", DEFAULT_MAX_RESULTS));

        String pageToken = PlaygroundParameters.text(parameters, "pageToken", "");
        if (!pageToken.isEmpty()) {
            request.setPageToken(pageToken);
        }
        return request.execute();
    }
}
