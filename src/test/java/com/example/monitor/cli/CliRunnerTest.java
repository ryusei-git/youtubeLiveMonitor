package com.example.monitor.cli;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.Platform;
import com.example.monitor.service.MonitoredChannelService;
import com.example.monitor.service.YouTubeApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Picocli による実際のコマンド解析・実行の配線を検証する。
 * Spring コンテキストは使わず、各コマンドをモックのサービスで手組みした
 * {@link CommandLine.IFactory} を使うことで、実際の {@code new CommandLine(...)} の
 * 挙動（サブコマンドの解決・終了コードの伝播）まで含めて検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CliRunner")
class CliRunnerTest {

    @Mock
    private MonitoredChannelService monitoredChannelService;

    @Mock
    private YouTubeApiClient youTubeApiClient;

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void redirectStreams() {
        originalOut = System.out;
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
    }

    private CliRunner buildRunner() throws Exception {
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == RootCommand.class) {
                    return (K) new RootCommand();
                }
                if (cls == ChannelCommand.class) {
                    return (K) new ChannelCommand();
                }
                if (cls == ChannelAddCommand.class) {
                    return (K) new ChannelAddCommand(monitoredChannelService);
                }
                if (cls == ChannelListCommand.class) {
                    return (K) new ChannelListCommand(monitoredChannelService);
                }
                if (cls == ChannelRemoveCommand.class) {
                    return (K) new ChannelRemoveCommand(monitoredChannelService);
                }
                if (cls == ChannelSearchCommand.class) {
                    return (K) new ChannelSearchCommand(youTubeApiClient);
                }
                if (cls == ChannelRecordCommand.class) {
                    return (K) new ChannelRecordCommand(monitoredChannelService);
                }
                return CommandLine.defaultFactory().create(cls);
            }
        };
        return new CliRunner(factory, factory.create(RootCommand.class));
    }

    @Nested
    @DisplayName("run()")
    class Run {

        @Test
        @DisplayName("正常系：channel listを実行するとMonitoredChannelServiceが呼ばれ終了コード0になる")
        void testMethod01() throws Exception {
            when(monitoredChannelService.findAll()).thenReturn(List.of());
            CliRunner runner = buildRunner();

            runner.run("channel", "list");

            verify(monitoredChannelService).findAll();
            assertThat(runner.getExitCode()).isZero();
            assertThat(outContent.toString()).contains("登録されているチャンネルはありません");
        }

        @Test
        @DisplayName("正常系：channel addを実行するとMonitoredChannelServiceに登録内容が渡される")
        void testMethod02() throws Exception {
            MonitoredChannel registered = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            registered.setId(1L);
            when(monitoredChannelService.register(Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", false, null)).thenReturn(registered);
            CliRunner runner = buildRunner();

            runner.run("channel", "add", "-i", "UCxxxxxxxx", "-n", "テストチャンネル");

            verify(monitoredChannelService).register(
                    Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", false, null);
            assertThat(runner.getExitCode()).isZero();
        }

        @Test
        @DisplayName("正常系：channel addに-pを付けるとそのプラットフォームで登録される")
        void testMethod05() throws Exception {
            MonitoredChannel registered = new MonitoredChannel(
                    Platform.TWITCH, "12826", "テスト配信者", false, null);
            registered.setId(1L);
            when(monitoredChannelService.register(
                    Platform.TWITCH, "foo", "テスト配信者", false, null)).thenReturn(registered);
            CliRunner runner = buildRunner();

            runner.run("channel", "add", "-p", "TWITCH", "-i", "foo", "-n", "テスト配信者");

            verify(monitoredChannelService).register(Platform.TWITCH, "foo", "テスト配信者", false, null);
            assertThat(runner.getExitCode()).isZero();
        }

        @Test
        @DisplayName("正常系：channel recordを実行するとMonitoredChannelServiceに切り替え内容が渡される")
        void testMethod04() throws Exception {
            CliRunner runner = buildRunner();

            runner.run("channel", "record", "-i", "1", "--on");

            verify(monitoredChannelService).setRecordEnabled(1L, true);
            assertThat(runner.getExitCode()).isZero();
        }

        @Test
        @DisplayName("異常系：存在しないサブコマンドを指定すると終了コードが0以外になる")
        void testMethod03() throws Exception {
            CliRunner runner = buildRunner();

            runner.run("channel", "unknown-command");

            assertThat(runner.getExitCode()).isNotZero();
        }
    }

    @Nested
    @DisplayName("getExitCode()")
    class GetExitCode {

        @Test
        @DisplayName("正常系：run()を呼ぶ前はデフォルト値0を返す")
        void testMethod01() {
            CliRunner runner = new CliRunner(CommandLine.defaultFactory(), new RootCommand());

            assertThat(runner.getExitCode()).isZero();
        }
    }
}
