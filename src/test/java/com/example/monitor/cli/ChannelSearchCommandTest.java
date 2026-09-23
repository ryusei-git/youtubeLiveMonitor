package com.example.monitor.cli;

import com.example.monitor.dto.ChannelSearchResult;
import com.example.monitor.service.YouTubeApiClient;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelSearchCommand")
class ChannelSearchCommandTest {

    @Mock
    private YouTubeApiClient youTubeApiClient;

    @InjectMocks
    private ChannelSearchCommand command;

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
        @DisplayName("正常系：該当チャンネルが見つかった場合は一覧を表示し0を返す")
        void testMethod01() {
            ReflectionTestUtils.setField(command, "channelName", "テスト");
            when(youTubeApiClient.searchChannelsByName("テスト"))
                    .thenReturn(List.of(new ChannelSearchResult("UCxxxxxxxx", "テストチャンネル", "https://example.com/icon.jpg")));

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("UCxxxxxxxx").contains("テストチャンネル");
        }

        @Test
        @DisplayName("正常系：該当チャンネルが見つからない場合は案内メッセージを表示し0を返す")
        void testMethod02() {
            ReflectionTestUtils.setField(command, "channelName", "存在しない名前");
            when(youTubeApiClient.searchChannelsByName("存在しない名前")).thenReturn(List.of());

            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("見つかりませんでした");
        }
    }
}
