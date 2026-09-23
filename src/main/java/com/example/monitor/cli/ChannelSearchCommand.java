package com.example.monitor.cli;

import com.example.monitor.dto.ChannelSearchResult;
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
 * 1 日あたりの実行回数には注意すること（既定の上限は 10,000）。
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
     * @return 常に 0（該当なしもエラーではないため）
     */
    @Override
    public Integer call() {
        List<ChannelSearchResult> searchResults = youTubeApiClient.searchChannelsByName(channelName);

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
