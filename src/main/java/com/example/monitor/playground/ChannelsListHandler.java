package com.example.monitor.playground;

import com.google.api.services.youtube.YouTube;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code channels.list} のお試し実行。チャンネルの情報を取得する。
 *
 * <p>チャンネルID（{@code UC...}）とハンドル（{@code @foo}）のどちらでも引ける。
 * ハンドルからチャンネルIDを調べる用途は、監視機能のチャンネル登録時にも使っている
 * （{@code YouTubeApiClient.resolveHandleToChannelId}）。
 */
@Component
@RequiredArgsConstructor
public class ChannelsListHandler implements PlaygroundApiHandler {

    /** 何も入力しなかった場合に取得する part。 */
    private static final String DEFAULT_PARTS = "snippet,statistics,contentDetails";

    private final YouTube youtube;

    @Override
    public ApiDefinition definition() {
        return new ApiDefinition(
                "channels.list",
                "チャンネル情報",
                "チャンネルID または ハンドルから、チャンネル名・登録者数・アップロード再生リストIDなどを取得します。"
                        + "どちらか一方だけ入力してください。",
                1,
                List.of(
                        ApiParameter.optional("id", "チャンネルID", "UCmzXkdzeCaAfXs2mD0JeMZA"),
                        ApiParameter.optional("forHandle", "ハンドル", "@example"),
                        ApiParameter.optional("part", "取得する part", DEFAULT_PARTS)));
    }

    @Override
    public Object execute(Map<String, String> parameters) throws IOException {
        YouTube.Channels.List request = youtube.channels()
                .list(PlaygroundParameters.list(parameters, "part", DEFAULT_PARTS));

        String channelId = PlaygroundParameters.text(parameters, "id", "");
        String handle = PlaygroundParameters.text(parameters, "forHandle", "");
        if (!channelId.isEmpty()) {
            request.setId(List.of(channelId.split(",")));
        } else if (!handle.isEmpty()) {
            request.setForHandle(handle);
        }
        // どちらも未入力ならそのまま送る。YouTube が返すエラーの内容自体が学びになるため

        return request.execute();
    }
}
