package com.example.monitor.playground;

import com.google.api.services.youtube.YouTube;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code search.list} のお試し実行。<b>1回で100クォータを消費する。</b>
 *
 * <p>1日の上限（既定10,000）に対して1回100は重く、100回試すと上限に達する。
 * そうなると監視機能側の {@code videos.list}（配信検知後の詳細取得）まで失敗するようになるため、
 * 画面側でも実行前に確認を挟んでいる。
 *
 * <p>安く済ませられる場面では他のAPIを選ぶこと。たとえば「チャンネルの投稿動画を一覧したい」なら、
 * {@code channels.list}（1）でアップロード再生リストIDを取り、
 * {@code playlistItems.list}（1）でたどる方が合計2クォータで済む。
 */
@Component
@RequiredArgsConstructor
public class SearchListHandler implements PlaygroundApiHandler {

    /** search.list は snippet しか返せない。 */
    private static final String DEFAULT_PARTS = "snippet";

    /** 1回あたりの既定取得件数。件数を増やしてもクォータは変わらない（1回100のまま）。 */
    private static final long DEFAULT_MAX_RESULTS = 10L;

    private final YouTube youtube;

    @Override
    public ApiDefinition definition() {
        return new ApiDefinition(
                "search.list",
                "検索",
                "キーワードで動画・チャンネル・再生リストを検索します。"
                        + "1回100クォータを消費するため、多用すると監視機能まで巻き添えで失敗します。"
                        + "件数を増やしてもクォータは変わらないので、試すなら1回で多めに取る方が得です。",
                100,
                List.of(
                        ApiParameter.optional("q", "検索キーワード", "ASMR"),
                        ApiParameter.optional("type", "種別", "video / channel / playlist"),
                        ApiParameter.optional("channelId", "チャンネルIDで絞る", "UCmzXkdzeCaAfXs2mD0JeMZA"),
                        ApiParameter.optional("order", "並び順", "date / rating / viewCount / relevance"),
                        ApiParameter.optional("maxResults", "取得件数（最大50）", String.valueOf(DEFAULT_MAX_RESULTS))));
    }

    @Override
    public Object execute(Map<String, String> parameters) throws IOException {
        YouTube.Search.List request = youtube.search()
                .list(List.of(DEFAULT_PARTS))
                .setMaxResults(PlaygroundParameters.count(parameters, "maxResults", DEFAULT_MAX_RESULTS));

        applyIfPresent(parameters, "q", request::setQ);
        applyIfPresent(parameters, "channelId", request::setChannelId);
        applyIfPresent(parameters, "order", request::setOrder);

        String type = PlaygroundParameters.text(parameters, "type", "");
        if (!type.isEmpty()) {
            request.setType(List.of(type.split(",")));
        }
        return request.execute();
    }

    /**
     * 入力があるときだけ設定を適用する。
     *
     * <p>空文字をそのまま渡すと「空のキーワードで検索」という別の意味になってしまうため、
     * 未入力の項目はリクエストに含めない。
     *
     * @param parameters 入力値
     * @param name       パラメータ名
     * @param setter     適用先
     */
    private void applyIfPresent(Map<String, String> parameters, String name,
                                java.util.function.Consumer<String> setter) {
        String value = PlaygroundParameters.text(parameters, name, "");
        if (!value.isEmpty()) {
            setter.accept(value);
        }
    }
}
