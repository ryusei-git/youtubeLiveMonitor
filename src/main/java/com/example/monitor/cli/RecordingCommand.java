package com.example.monitor.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code recording} サブコマンド。録画に関する操作を束ねる。
 *
 * <p>単体では何もせず、下のサブコマンド（{@code mp3}）へ処理を委ねる（{@link SoundCommand} と同じ形）。
 */
@Component
@Command(
        name = "recording",
        mixinStandardHelpOptions = true,
        description = "録画を扱う",
        subcommands = {RecordingMp3Command.class})
public class RecordingCommand implements Runnable {

    /** サブコマンドが指定されなかった場合に使い方を表示する。 */
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
