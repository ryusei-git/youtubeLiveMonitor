package com.example.monitor.cli;

import com.example.monitor.exception.ChannelNotFoundException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelRecordCommand")
class ChannelRecordCommandTest {

    @Mock
    private MonitoredChannelService monitoredChannelService;

    @InjectMocks
    private ChannelRecordCommand command;

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
        @DisplayName("正常系：--onを指定すると録画を有効にして0を返す")
        void testMethod01() {
            ReflectionTestUtils.setField(command, "channelRecordId", 1L);
            ReflectionTestUtils.setField(command, "on", true);
            ReflectionTestUtils.setField(command, "off", false);

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            verify(monitoredChannelService).setRecordEnabled(1L, true);
            assertThat(outContent.toString()).contains("ON");
        }

        @Test
        @DisplayName("正常系：--offを指定すると録画を無効にして0を返す")
        void testMethod02() {
            ReflectionTestUtils.setField(command, "channelRecordId", 1L);
            ReflectionTestUtils.setField(command, "on", false);
            ReflectionTestUtils.setField(command, "off", true);

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            verify(monitoredChannelService).setRecordEnabled(1L, false);
            assertThat(outContent.toString()).contains("OFF");
        }

        @Test
        @DisplayName("異常系：--onも--offも指定しない場合はエラーメッセージを表示し1を返す")
        void testMethod03() {
            ReflectionTestUtils.setField(command, "channelRecordId", 1L);
            ReflectionTestUtils.setField(command, "on", false);
            ReflectionTestUtils.setField(command, "off", false);

            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("--on か --off");
        }

        @Test
        @DisplayName("異常系：--onと--offを両方指定した場合はエラーメッセージを表示し1を返す")
        void testMethod04() {
            ReflectionTestUtils.setField(command, "channelRecordId", 1L);
            ReflectionTestUtils.setField(command, "on", true);
            ReflectionTestUtils.setField(command, "off", true);

            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系：存在しないIDを指定するとエラーメッセージを表示し1を返す")
        void testMethod05() {
            ReflectionTestUtils.setField(command, "channelRecordId", 999L);
            ReflectionTestUtils.setField(command, "on", true);
            ReflectionTestUtils.setField(command, "off", false);
            doThrow(new ChannelNotFoundException(999L)).when(monitoredChannelService).setRecordEnabled(999L, true);

            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("チャンネルが見つかりません");
        }

        @Test
        @DisplayName("正常系：-kを指定するとタイトルフィルターも合わせて更新される")
        void testMethod06() {
            ReflectionTestUtils.setField(command, "channelRecordId", 1L);
            ReflectionTestUtils.setField(command, "on", true);
            ReflectionTestUtils.setField(command, "off", false);
            ReflectionTestUtils.setField(command, "titleKeywords", "【ASMR】,【生配信】");

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            verify(monitoredChannelService).setRecordEnabled(1L, true);
            verify(monitoredChannelService).setRecordTitleKeywords(1L, "【ASMR】,【生配信】");
            assertThat(outContent.toString()).contains("【ASMR】,【生配信】");
        }

        @Test
        @DisplayName("正常系：-kを指定しない場合はタイトルフィルターを更新しない")
        void testMethod07() {
            ReflectionTestUtils.setField(command, "channelRecordId", 1L);
            ReflectionTestUtils.setField(command, "on", true);
            ReflectionTestUtils.setField(command, "off", false);

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            verify(monitoredChannelService, never()).setRecordTitleKeywords(any(), any());
        }
    }
}
