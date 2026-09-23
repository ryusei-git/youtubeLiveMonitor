package com.example.monitor.playground;

import com.google.api.services.youtube.YouTube;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code playlistItems.list} のお試し実行。再生リストに入っている動画を一覧する。
 *
 * <p>チャンネルの「アップロード済み動画」も内部的には再生リストなので、
 * {@code channels.list} の {@code contentDetails.relatedPlaylists.uploads} で得た ID を
 * ここへ渡すと、そのチャンネルの投稿動画を順に取得できる。
 */
@Component
@RequiredArgsConstructor
public class PlaylistItemsListHandler implements PlaygroundApiHandler {

    /** 何も入力しなかった場合に取得する part。 */
    private static final String DEFAULT_PARTS = "snippet,contentDetails";

    /** 1回あたりの既定取得件数。上限は 50。 */
    private static final long DEFAULT_MAX_RESULTS = 10L;

    private final YouTube youtube;

    @Override
    public ApiDefinition definition() {
        return new ApiDefinition(
                "playlistItems.list",
                "再生リストの中身",
                "再生リストIDから、含まれる動画の一覧を取得します。"
                        + "nextPageToken を次回の pageToken に渡すと続きが読めます。",
                1,
                List.of(
                        ApiParameter.required("playlistId", "再生リストID", "PLxxxxxxxx または UUxxxxxxxx"),
                        ApiParameter.optional("maxResults", "取得件数（最大50）", String.valueOf(DEFAULT_MAX_RESULTS)),
                        ApiParameter.optional("pageToken", "続きを読む場合のトークン", "前回の nextPageToken"),
                        ApiParameter.optional("part", "取得する part", DEFAULT_PARTS)));
    }

    @Override
    public Object execute(Map<String, String> parameters) throws IOException {
        YouTube.PlaylistItems.List request = youtube.playlistItems()
                .list(PlaygroundParameters.list(parameters, "part", DEFAULT_PARTS))
                .setPlaylistId(PlaygroundParameters.text(parameters, "playlistId", ""))
                .setMaxResults(PlaygroundParameters.count(parameters, "maxResults", DEFAULT_MAX_RESULTS));

        String pageToken = PlaygroundParameters.text(parameters, "pageToken", "");
        if (!pageToken.isEmpty()) {
            request.setPageToken(pageToken);
        }
        return request.execute();
    }
}
