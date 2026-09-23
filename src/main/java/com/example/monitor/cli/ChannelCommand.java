package com.example.monitor.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code channel} サブコマンド。監視対象チャンネルに関する操作を束ねる。
 *
 * <p>単体では何もせず、さらに下のサブコマンド（{@code add}、{@code list} など）へ処理を委ねる。
 */
@Component
@Command(
        name = "channel",
        mixinStandardHelpOptions = true,
        description = "監視対象チャンネルを管理する",
        subcommands = {
                ChannelAddCommand.class,
                ChannelListCommand.class,
                ChannelRemoveCommand.class,
                ChannelSearchCommand.class,
                ChannelRecordCommand.class
        })
public class ChannelCommand implements Runnable {

    /** サブコマンドが指定されなかった場合に使い方を表示する。 */
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
