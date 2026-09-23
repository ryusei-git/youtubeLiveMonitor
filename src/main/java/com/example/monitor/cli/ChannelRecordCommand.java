package com.example.monitor.cli;

import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.service.MonitoredChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code channel record} コマンド。登録済みチャンネルの自動録画 ON/OFF・タイトルフィルターを設定する。
 *
 * <p>実行例:
 * <pre>{@code
 * java -jar app.jar channel record -i 3 --on
 * java -jar app.jar channel record -i 3 --off
 * java -jar app.jar channel record -i 3 --on -k "ASMR,生配信"
 * }</pre>
 */
@Component
@Command(
        name = "record",
        mixinStandardHelpOptions = true,
        description = "登録済みチャンネルの自動録画 ON/OFF を切り替える")
@RequiredArgsConstructor
public class ChannelRecordCommand implements Callable<Integer> {

    private final MonitoredChannelService monitoredChannelService;

    @Option(names = {"-i", "--id"}, required = true,
            description = "対象の監視対象ID（channel list の先頭列。YouTube のチャンネルIDではない）")
    private Long channelRecordId;

    @Option(names = "--on", description = "録画を有効にする")
    private boolean on;

    @Option(names = "--off", description = "録画を無効にする")
    private boolean off;

    @Option(names = {"-k", "--keywords"},
            description = "通知・録画の対象を絞り込むタイトルキーワード（カンマ区切り、いずれか1つでも" +
                    "配信タイトルに含まれていれば対象。含まれない配信は通知も録画もしない）。" +
                    "空文字を指定すると絞り込みを解除する")
    private String titleKeywords;

    /**
     * 録画設定を切り替える。
     *
     * @return 成功なら 0、{@code --on}/{@code --off} のどちらも指定していない、または
     *         対象IDが存在しない場合は 1
     */
    @Override
    public Integer call() {
        if (on == off) {
            System.err.println("--on か --off のどちらか一方を指定してください");
            return 1;
        }

        try {
            monitoredChannelService.setRecordEnabled(channelRecordId, on);
            if (titleKeywords != null) {
                monitoredChannelService.setRecordTitleKeywords(channelRecordId, titleKeywords);
            }
            System.out.printf("録画設定を変更しました: ID %d → %s%s%n", channelRecordId, on ? "ON" : "OFF",
                    titleKeywords != null ? " (キーワード: " + titleKeywords + ")" : "");
            return 0;
        } catch (ChannelNotFoundException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
