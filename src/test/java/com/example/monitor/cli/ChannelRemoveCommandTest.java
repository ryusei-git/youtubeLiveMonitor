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
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelRemoveCommand")
class ChannelRemoveCommandTest {

    @Mock
    private MonitoredChannelService monitoredChannelService;

    @InjectMocks
    private ChannelRemoveCommand command;

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
        @DisplayName("正常系：削除に成功した場合はメッセージを表示し0を返す")
        void testMethod01() {
            ReflectionTestUtils.setField(command, "channelRecordId", 1L);

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("削除しました").contains("1");
        }

        @Test
        @DisplayName("異常系：存在しないIDの場合はエラーメッセージを表示し1を返す")
        void testMethod02() {
            ReflectionTestUtils.setField(command, "channelRecordId", 999L);
            doThrow(new ChannelNotFoundException(999L)).when(monitoredChannelService).remove(999L);

            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("チャンネルが見つかりません");
        }
    }
}
