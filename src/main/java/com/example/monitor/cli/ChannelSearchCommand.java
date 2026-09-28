package com.example.monitor.cli;

import com.example.monitor.dto.ChannelSearchResult;
import com.example.monitor.exception.SearchQuotaExceededException;
import com.example.monitor.service.YouTubeApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code channel search} コマンド。チャンネル名からチャンネル ID を調べる。
 *
 * <p>チャンネル ID が分からないときの補助手段。1 回で 100 クォータを消費するため、
 * 回数は {@link YouTubeApiClient#searchChannelsByName} の中で、利用者の検索・新人発掘と同じ 1 日の上限に数える
 * （常駐のサービスと同じ DB の行を数える。H2 を {@code AUTO_SERVER=TRUE} で開いているため）。
 * 上限に達していれば、理由を標準エラーに出して 1 で終わる。
 * 表示された ID を {@code channel add} に渡して登録する流れになる。
 */
@Component
@Command(
        name = "search",
        mixinStandardHelpOptions = true,
        description = "チャンネル名で検索してチャンネルIDを調べる（100クォータを消費）")
@RequiredArgsConstructor
public class ChannelSearchCommand implements Callable<Integer> {

    /** 検索結果の書式。チャンネルID / 名前 の 2 列。 */
    private static final String ROW_FORMAT = "%-26s %s%n";

    private final YouTubeApiClient youTubeApiClient;

    @Option(names = {"-n", "--name"}, required = true,
            description = "検索するチャンネル名（部分一致）")
    private String channelName;

    /**
     * チャンネルを検索して候補を表示する。
     *
     * @return 表示できれば 0（該当なしもエラーではないため）。検索の回数が本日の上限なら 1
     */
    @Override
    public Integer call() {
        List<ChannelSearchResult> searchResults;
        try {
            searchResults = youTubeApiClient.searchChannelsByName(channelName);
        } catch (SearchQuotaExceededException e) {
            System.err.println(e.getMessage());
            System.err.println("チャンネル名の検索は、利用者の検索と同じ 1 日の回数を使います"
                    + "（上限は .env の YOUTUBE_SEARCH_DAILY_LIMIT から YOUTUBE_SEARCH_DISCOVERY_LIMIT を引いた回数）");
            return 1;
        }

        if (searchResults.isEmpty()) {
            System.out.println("該当するチャンネルが見つかりませんでした");
            return 0;
        }

        System.out.printf(ROW_FORMAT, "チャンネルID", "名前");
        for (ChannelSearchResult result : searchResults) {
            System.out.printf(ROW_FORMAT, result.youtubeChannelId(), result.channelTitle());
        }
        return 0;
    }
}
