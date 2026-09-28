package com.example.monitor;

import com.example.monitor.cli.ChannelListCommand;
import com.example.monitor.cli.CliRunner;
import com.example.monitor.cli.RootCommand;
import com.example.monitor.cli.SoundDetectCommand;
import com.example.monitor.scheduler.LiveStreamPollingScheduler;
import com.example.monitor.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI モード（{@code java -jar app.jar channel list}）で Spring が起動し、すべてのコマンドを作れることを確かめる。
 *
 * <p>Web でしか使わない {@code @Profile("!cli")} の Bean に、CLI でも作られる Bean が依存すると、
 * CLI が丸ごと起動できなくなる（{@code MonitoringController} で実際に発生した。docs/pitfalls.md）。
 * {@code CliRunnerTest} はコマンドを手組みして Spring を通さないので、この壊れ方を検出できない。
 * そこで {@link YouTubeLiveMonitorApplication#main(String[])} の CLI の分岐と同じ条件
 * （Web サーバーなし・{@code cli} プロファイル・本番の {@code application-cli.yml} の遅延初期化）でコンテキストを起動し、
 * 実際に {@code channel list} を実行させる。
 *
 * <p>picocli（4.7.6）はサブコマンドのオブジェクトを、そのコマンドを実行するときに初めて作る。
 * {@code channel list} で作られるのは {@code channel} と {@code list} だけなので、ほかのコマンドの依存の誤りは
 * {@code channel list} では分からない（本番では、そのコマンドを実行したときに初めて終了コード 1 で失敗する）。
 * そのため {@link RootCommand} から {@code @Command(subcommands = ...)} をたどり、すべてのコマンドの Bean を作らせる。
 * picocli の Spring 用ファクトリーは最初に Spring から Bean を取り出すので、Bean を作れれば本番でも作れる。
 * {@code getBean()} で作らせるのは、失敗したときの例外に「どの Bean が無いか」が出るため。
 *
 * <p>{@code main()} そのものは呼ばない。最後の {@code System.exit} でテストの JVM ごと終わるため。
 * {@link CliRunner} は終了コードを持つだけで {@code System.exit} を呼ばないので、Bean から読んで確かめる。
 *
 * <p>DB の URL をここで指定する理由は 2 つある。
 * <ul>
 *   <li>ほかの {@code @SpringBootTest} は {@code jdbc:h2:mem:testdb} を使う。同じ JVM の中の名前付きのインメモリ DB は
 *       共有されるので、同じ名前だとこのコンテキストの {@code create-drop} が先に起動したコンテキストの表を消して作り直し、
 *       初期管理者などが消えてほかのテストが落ちうる。</li>
 *   <li>{@code application-cli.yml} に DB の URL が書かれても、本番の {@code data/monitor} に触れないため
 *       （{@code @SpringBootTest} の {@code properties} はプロファイルの設定ファイルより優先される）。</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        args = {"channel", "list"},
        properties = "spring.datasource.url=jdbc:h2:mem:clitest;DB_CLOSE_DELAY=-1")
@ActiveProfiles("cli")
@DisplayName("YouTubeLiveMonitorApplication")
class YouTubeLiveMonitorApplicationTest {

    @Autowired
    private CliRunner cliRunner;

    @Autowired
    private ApplicationContext context;

    /** {@code commandClass} と、その下の {@code @Command(subcommands = ...)} をたどったすべてのコマンドのクラスを集める。 */
    private static void collectCommandClasses(Class<?> commandClass, List<Class<?>> result) {
        result.add(commandClass);
        for (Class<?> subcommand : commandClass.getAnnotation(CommandLine.Command.class).subcommands()) {
            collectCommandClasses(subcommand, result);
        }
    }

    @Nested
    @DisplayName("main()：CLI モード")
    class Main {

        @Test
        @DisplayName("正常系：cli プロファイルでコンテキストが起動し、channel list の終了コードが 0 になる")
        void testMethod01() {
            assertThat(cliRunner.getExitCode()).isZero();
        }

        @Test
        @DisplayName("正常系：Web 専用（@Profile(\"!cli\")）の Bean を作らない")
        void testMethod02() {
            assertThat(context.getBeanNamesForType(LiveStreamPollingScheduler.class)).isEmpty();
            assertThat(context.getBeanNamesForType(SecurityConfig.class)).isEmpty();
        }

        @Test
        @DisplayName("正常系：本番の application-cli.yml を読み、遅延初期化で起動している")
        void testMethod03() {
            assertThat(context.getEnvironment().getProperty("spring.main.lazy-initialization", Boolean.class))
                    .isTrue();
        }

        @Test
        @DisplayName("正常系：channel list では作られないものも含め、すべてのコマンドの Bean を作れる")
        void testMethod04() {
            List<Class<?>> commandClasses = new ArrayList<>();
            collectCommandClasses(RootCommand.class, commandClasses);

            assertThat(commandClasses).contains(ChannelListCommand.class);
            assertThat(commandClasses).contains(SoundDetectCommand.class);
            for (Class<?> commandClass : commandClasses) {
                Object command = context.getBean(commandClass);
                assertThat(command).isNotNull();
            }
        }
    }
}
