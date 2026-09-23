package com.example.monitor.cli;

import com.example.monitor.entity.MonitoredChannel;
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelListCommand")
class ChannelListCommandTest {

    @Mock
    private MonitoredChannelService monitoredChannelService;

    @InjectMocks
    private ChannelListCommand command;

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

    @Nested
    @DisplayName("call()")
    class Call {

        @Test
        @DisplayName("正常系：チャンネルが0件の場合は案内メッセージを表示し0を返す")
        void testMethod01() {
            when(monitoredChannelService.findAll()).thenReturn(List.of());

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("登録されているチャンネルはありません");
        }

        @Test
        @DisplayName("正常系：チャンネルが存在する場合は一覧を表示し0を返す")
        void testMethod02() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setId(1L);
            channel.setCurrentlyLive(true);
            when(monitoredChannelService.findAll()).thenReturn(List.of(channel));

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = outContent.toString();
            assertThat(output).contains("UCxxxxxxxx").contains("テストチャンネル").contains("配信中");
        }

        @Test
        @DisplayName("正常系：録画有効なチャンネルはON、無効なチャンネルは-と表示される")
        void testMethod03() {
            MonitoredChannel recording = new MonitoredChannel("UCrecord00", "録画中チャンネル", true);
            recording.setId(1L);
            MonitoredChannel notRecording = new MonitoredChannel("UCnorecord", "録画しないチャンネル", false);
            notRecording.setId(2L);
            when(monitoredChannelService.findAll()).thenReturn(List.of(recording, notRecording));

            command.call();

            List<String> lines = outContent.toString().lines().toList();
            assertThat(lines.get(1)).contains("ON");
            assertThat(lines.get(2)).contains("-");
        }
    }
}
