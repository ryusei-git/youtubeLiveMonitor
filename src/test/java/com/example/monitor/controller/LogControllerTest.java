package com.example.monitor.controller;

import com.example.monitor.dto.LogEntry;
import com.example.monitor.dto.LogViewResponse;
import com.example.monitor.service.ChannelLogReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LogController")
class LogControllerTest {

    @Mock
    private ChannelLogReader channelLogReader;

    @InjectMocks
    private LogController controller;

    @Nested
    @DisplayName("listChannelsWithLogs()")
    class ListChannelsWithLogs {

        @Test
        @DisplayName("正常系：ChannelLogReaderが返す一覧をそのまま返す")
        void testMethod01() {
            when(channelLogReader.listChannelsWithLogs()).thenReturn(List.of("UCxxxxxxxx"));

            List<String> result = controller.listChannelsWithLogs();

            assertThat(result).containsExactly("UCxxxxxxxx");
        }
    }

    @Nested
    @DisplayName("getChannelLog()")
    class GetChannelLog {

        @Test
        @DisplayName("正常系：指定チャンネルのログとレベルの一覧を取得する")
        void testMethod01() {
            LogEntry entry = new LogEntry("2026-09-13 10:00:00", "INFO", "c.e.m.Foo", "配信を検知しました");
            LogViewResponse view = new LogViewResponse(List.of("INFO"), List.of(entry));
            when(channelLogReader.readChannelLog("UCxxxxxxxx", 200, null)).thenReturn(view);

            LogViewResponse result = controller.getChannelLog("UCxxxxxxxx", 200, null);

            assertThat(result.entries()).containsExactly(entry);
            assertThat(result.availableLevels()).containsExactly("INFO");
        }

        @Test
        @DisplayName("正常系：レベル指定はそのままChannelLogReaderへ渡される")
        void testMethod02() {
            LogViewResponse view = new LogViewResponse(List.of("ERROR", "INFO"), List.of());
            when(channelLogReader.readChannelLog("UCxxxxxxxx", 200, "ERROR")).thenReturn(view);

            LogViewResponse result = controller.getChannelLog("UCxxxxxxxx", 200, "ERROR");

            assertThat(result.availableLevels()).containsExactly("ERROR", "INFO");
        }
    }

    @Nested
    @DisplayName("getSystemLog()")
    class GetSystemLog {

        @Test
        @DisplayName("正常系：システムログとレベルの一覧を取得する")
        void testMethod01() {
            LogEntry entry = new LogEntry("2026-09-13 10:00:00", "INFO", "c.e.m.App", "起動しました");
            LogViewResponse view = new LogViewResponse(List.of("INFO"), List.of(entry));
            when(channelLogReader.readSystemLog(200, null)).thenReturn(view);

            LogViewResponse result = controller.getSystemLog(200, null);

            assertThat(result.entries()).containsExactly(entry);
        }

        @Test
        @DisplayName("正常系：レベル指定はそのままChannelLogReaderへ渡される")
        void testMethod02() {
            LogViewResponse view = new LogViewResponse(List.of("ERROR"), List.of());
            when(channelLogReader.readSystemLog(200, "ERROR")).thenReturn(view);

            LogViewResponse result = controller.getSystemLog(200, "ERROR");

            assertThat(result.entries()).isEmpty();
        }
    }
}
