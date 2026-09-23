package com.example.monitor.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * CLI モードで起動されたときに、Picocli へ処理を引き渡す橋渡し役。
 *
 * <p>{@code cli} プロファイルが有効なとき（＝起動引数が指定されたとき）だけ動く。
 * {@link ExitCodeGenerator} を実装しているため、各コマンドが返した終了コードが
 * そのままプロセスの終了コードになり、シェルスクリプトから成否を判定できる。
 *
 * @see com.example.monitor.YouTubeLiveMonitorApplication 起動モードの分岐
 */
@Component
@Profile("cli")
@RequiredArgsConstructor
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    /** Picocli のコマンドオブジェクトを Spring の Bean として解決するためのファクトリ。 */
    private final IFactory picocliFactory;

    private final RootCommand rootCommand;

    /** 実行したコマンドが返した終了コード。 */
    private int exitCode;

    /**
     * コマンドライン引数を解析して該当のコマンドを実行する。
     *
     * @param args 起動時の引数
     */
    @Override
    public void run(String... args) {
        exitCode = new CommandLine(rootCommand, picocliFactory).execute(args);
    }

    /**
     * プロセスの終了コードを返す。Spring Boot が終了処理の中で呼び出す。
     *
     * @return 実行したコマンドの終了コード
     */
    @Override
    public int getExitCode() {
        return exitCode;
    }
}
