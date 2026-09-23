package com.example.monitor.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelNotFoundException")
class ChannelNotFoundExceptionTest {

    @Nested
    @DisplayName("コンストラクタ")
    class Constructor {

        @Test
        @DisplayName("正常系：主キーを含むメッセージが設定される")
        void testMethod01() {
            ChannelNotFoundException exception = new ChannelNotFoundException(123L);

            assertThat(exception.getMessage()).isEqualTo("チャンネルが見つかりません: id=123");
        }

        @Test
        @DisplayName("正常系：RuntimeExceptionのサブクラスである")
        void testMethod02() {
            ChannelNotFoundException exception = new ChannelNotFoundException(123L);

            assertThat(exception).isInstanceOf(RuntimeException.class);
        }
    }
}
