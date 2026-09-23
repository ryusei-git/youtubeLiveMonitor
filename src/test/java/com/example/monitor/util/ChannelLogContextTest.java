package com.example.monitor.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChannelLogContext")
class ChannelLogContextTest {

    /** SiftingAppender が参照するキー。実装と同じ値でなければ振り分けが効かないため直接書く。 */
    private static final String MDC_CHANNEL_ID_KEY = "channelId";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("callWithChannel()")
    class CallWithChannel {

        @Test
        @DisplayName("正常系：処理中はMDCにチャンネルIDが設定され、戻り値がそのまま返る")
        void testMethod01() {
            String observed = ChannelLogContext.callWithChannel(
                    "UCxxxxxxxx", () -> MDC.get(MDC_CHANNEL_ID_KEY));

            assertThat(observed).isEqualTo("UCxxxxxxxx");
        }

        @Test
        @DisplayName("正常系：処理が終わるとMDCは元の未設定状態に戻る")
        void testMethod02() {
            ChannelLogContext.callWithChannel("UCxxxxxxxx", () -> null);

            assertThat(MDC.get(MDC_CHANNEL_ID_KEY)).isNull();
        }

        @Test
        @DisplayName("正常系：入れ子で呼んでも外側のチャンネルIDが復元される")
        void testMethod03() {
            ChannelLogContext.callWithChannel("UCouter000", () -> {
                ChannelLogContext.callWithChannel("UCinner000", () -> null);
                // 内側が終わった時点で外側の印が消えると、以降のログが振り分けから外れる
                assertThat(MDC.get(MDC_CHANNEL_ID_KEY)).isEqualTo("UCouter000");
                return null;
            });

            assertThat(MDC.get(MDC_CHANNEL_ID_KEY)).isNull();
        }

        @Test
        @DisplayName("異常系：処理が例外で終わってもMDCは後始末される")
        void testMethod04() {
            assertThatThrownBy(() -> ChannelLogContext.callWithChannel("UCxxxxxxxx", () -> {
                throw new IllegalStateException("想定外のエラー");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(MDC.get(MDC_CHANNEL_ID_KEY)).isNull();
        }
    }

    @Nested
    @DisplayName("runWithChannel()")
    class RunWithChannel {

        @Test
        @DisplayName("正常系：処理中はMDCにチャンネルIDが設定され、終了後は元に戻る")
        void testMethod01() {
            List<String> observed = new ArrayList<>();

            ChannelLogContext.runWithChannel("UCxxxxxxxx", () -> observed.add(MDC.get(MDC_CHANNEL_ID_KEY)));

            assertThat(observed).containsExactly("UCxxxxxxxx");
            assertThat(MDC.get(MDC_CHANNEL_ID_KEY)).isNull();
        }
    }
}
