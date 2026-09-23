package com.example.monitor.cli;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.platform.Platform;
import com.example.monitor.service.MonitoredChannelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelAddCommand")
class ChannelAddCommandTest {

    @Mock
    private MonitoredChannelService monitoredChannelService;

    @InjectMocks
    private ChannelAddCommand command;

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void redirectStreams() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Nested
    @DisplayName("call()")
    class Call {

        @Test
        @DisplayName("正常系：登録に成功した場合は登録内容を標準出力へ表示し0を返す")
        void testMethod01() {
            ReflectionTestUtils.setField(command, "youtubeChannelId", "UCxxxxxxxx");
            ReflectionTestUtils.setField(command, "channelName", "テストチャンネル");
            MonitoredChannel registered = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            registered.setId(1L);
            when(monitoredChannelService.register(Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", false, null)).thenReturn(registered);

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("登録しました").contains("UCxxxxxxxx");
        }

        @Test
        @DisplayName("正常系：--recordを指定すると録画有効として登録され、出力に[録画ON]が表示される")
        void testMethod02() {
            ReflectionTestUtils.setField(command, "youtubeChannelId", "UCxxxxxxxx");
            ReflectionTestUtils.setField(command, "channelName", "テストチャンネル");
            ReflectionTestUtils.setField(command, "recordEnabled", true);
            MonitoredChannel registered = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", true);
            registered.setId(1L);
            when(monitoredChannelService.register(Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", true, null)).thenReturn(registered);

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("[録画ON]");
        }

        @Test
        @DisplayName("異常系：既に登録済みの場合はエラーメッセージを標準エラー出力へ表示し1を返す")
        void testMethod03() {
            ReflectionTestUtils.setField(command, "youtubeChannelId", "UCxxxxxxxx");
            ReflectionTestUtils.setField(command, "channelName", "テストチャンネル");
            when(monitoredChannelService.register(Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", false, null))
                    .thenThrow(new ChannelAlreadyRegisteredException("UCxxxxxxxx"));

            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("既に登録されています");
        }
    }
}
