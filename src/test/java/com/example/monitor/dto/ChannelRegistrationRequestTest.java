package com.example.monitor.dto;

import com.example.monitor.platform.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelRegistrationRequest")
class ChannelRegistrationRequestTest {

    @Nested
    @DisplayName("platformOrDefault()")
    class PlatformOrDefault {

        @Test
        @DisplayName("正常系：指定されたプラットフォームをそのまま返す")
        void testMethod01() {
            ChannelRegistrationRequest request =
                    new ChannelRegistrationRequest(Platform.TWITCH, "foo", "テスト", false, null);

            assertThat(request.platformOrDefault()).isEqualTo(Platform.TWITCH);
        }

        @Test
        @DisplayName("正常系：未指定ならYouTubeとして扱う")
        void testMethod02() {
            // プラットフォーム選択を導入する前から動いている呼び出しを壊さないための既定値
            ChannelRegistrationRequest request =
                    new ChannelRegistrationRequest(null, "UCxxxxxxxx", "テスト", false, null);

            assertThat(request.platformOrDefault()).isEqualTo(Platform.YOUTUBE);
        }
    }
}
