package com.example.monitor.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelAlreadyRegisteredException")
class ChannelAlreadyRegisteredExceptionTest {

    @Nested
    @DisplayName("コンストラクタ")
    class Constructor {

        @Test
        @DisplayName("正常系：チャンネルIDを含むメッセージが設定される")
        void testMethod01() {
            ChannelAlreadyRegisteredException exception = new ChannelAlreadyRegisteredException("UCxxxxxxxx");

            assertThat(exception.getMessage()).isEqualTo("既に登録されています: UCxxxxxxxx");
        }

        @Test
        @DisplayName("正常系：RuntimeExceptionのサブクラスである")
        void testMethod02() {
            ChannelAlreadyRegisteredException exception = new ChannelAlreadyRegisteredException("UCxxxxxxxx");

            assertThat(exception).isInstanceOf(RuntimeException.class);
        }
    }
}
