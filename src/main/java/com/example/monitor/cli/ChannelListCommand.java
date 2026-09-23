package com.example.monitor.cli;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.service.MonitoredChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code channel list} コマンド。監視対象チャンネルの一覧を表示する。
 *
 * <p>先頭に表示される ID は削除コマンドで指定する値であり、YouTube のチャンネル ID とは別物。
 */
@Component
@Command(
        name = "list",
        mixinStandardHelpOptions = true,
        description = "監視中のチャンネル一覧を表示する")
@RequiredArgsConstructor
public class ChannelListCommand implements Callable<Integer> {

    /** 一覧の書式。ID / 配信元 / チャンネルID / 名前 / 状態 / 録画 / 最終通知動画ID の 7 列。 */
    private static final String ROW_FORMAT = "%-4s %-8s %-26s %-20s %-8s %-6s %s%n";

    private final MonitoredChannelService monitoredChannelService;

    /**
     * 監視対象を一覧表示する。
     *
     * @return 常に 0
     */
    @Override
    public Integer call() {
        List<MonitoredChannel> channels = monitoredChannelService.findAll();

        if (channels.isEmpty()) {
            System.out.println("登録されているチャンネルはありません");
            return 0;
        }

        System.out.printf(ROW_FORMAT, "ID", "配信元", "チャンネルID", "名前", "状態", "録画", "最終通知動画ID");
        for (MonitoredChannel channel : channels) {
            System.out.printf(ROW_FORMAT,
                    channel.getId(),
                    channel.getPlatform().displayName(),
                    channel.getYoutubeChannelId(),
                    channel.getChannelName(),
                    channel.isCurrentlyLive() ? "配信中" : "-",
                    channel.isRecordEnabled() ? "ON" : "-",
                    channel.getLastNotifiedVideoId() == null ? "-" : channel.getLastNotifiedVideoId());
        }
        return 0;
    }
}
