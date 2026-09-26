package com.example.monitor.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code sound} サブコマンド。録画の音声の解析に関する操作を束ねる。
 *
 * <p>単体では何もせず、下のサブコマンド（{@code detect}）へ処理を委ねる（{@link ChannelCommand} と同じ形）。
 */
@Component
@Command(
        name = "sound",
        mixinStandardHelpOptions = true,
        description = "録画の音声を解析する",
        subcommands = {SoundDetectCommand.class})
public class SoundCommand implements Runnable {

    /** サブコマンドが指定されなかった場合に使い方を表示する。 */
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
