package com.example.monitor.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI の入口となるコマンド。サブコマンドを束ねるだけで、単体では何もしない。
 *
 * <p>引数なしで実行された場合は使い方を表示する。
 */
@Component
@Command(
        name = "monitor",
        mixinStandardHelpOptions = true,
        description = "YouTube Live Monitor の管理コマンド",
        subcommands = {ChannelCommand.class, SoundCommand.class, SetPasswordCommand.class})
public class RootCommand implements Runnable {

    /** サブコマンドが指定されなかった場合に使い方を表示する。 */
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
