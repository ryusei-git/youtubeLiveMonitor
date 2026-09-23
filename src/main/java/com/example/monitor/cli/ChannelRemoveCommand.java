package com.example.monitor.cli;

import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.service.MonitoredChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code channel remove} コマンド。監視対象からチャンネルを削除する。
 *
 * <p>削除するとそのチャンネルの通知履歴も一緒に消える点に注意。
 */
@Component
@Command(
        name = "remove",
        mixinStandardHelpOptions = true,
        description = "監視対象からチャンネルを削除する（通知履歴も一緒に削除される）")
@RequiredArgsConstructor
public class ChannelRemoveCommand implements Callable<Integer> {

    private final MonitoredChannelService monitoredChannelService;

    @Option(names = {"-i", "--id"}, required = true,
            description = "削除する監視対象のID（channel list の先頭列。YouTube のチャンネルIDではない）")
    private Long channelRecordId;

    /**
     * 監視対象を削除する。
     *
     * @return 成功なら 0、指定 ID が存在しなければ 1
     */
    @Override
    public Integer call() {
        try {
            monitoredChannelService.remove(channelRecordId);
            System.out.println("削除しました: ID " + channelRecordId);
            return 0;
        } catch (ChannelNotFoundException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
