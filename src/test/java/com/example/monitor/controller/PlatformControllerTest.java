package com.example.monitor.controller;

import com.example.monitor.dto.PlatformResponse;
import com.example.monitor.platform.Platform;
import com.example.monitor.platform.StreamPlatform;
import com.example.monitor.platform.StreamPlatformRegistry;
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
@DisplayName("PlatformController")
class PlatformControllerTest {

    @Mock
    private StreamPlatformRegistry streamPlatformRegistry;

    @Mock
    private StreamPlatform youTubePlatform;

    @Mock
    private StreamPlatform twitchPlatform;

    @InjectMocks
    private PlatformController controller;

    @Nested
    @DisplayName("listPlatforms()")
    class ListPlatforms {

        @Test
        @DisplayName("正常系：登録されている実装を選択肢として返す")
        void testMethod01() {
            when(youTubePlatform.platform()).thenReturn(Platform.YOUTUBE);
            when(youTubePlatform.isAvailable()).thenReturn(true);
            when(youTubePlatform.inputHint()).thenReturn("URL / @ハンドル / チャンネルID（UC...）");
            when(streamPlatformRegistry.all()).thenReturn(List.of(youTubePlatform));

            List<PlatformResponse> platforms = controller.listPlatforms();

            assertThat(platforms).hasSize(1);
            assertThat(platforms.get(0).name()).isEqualTo(Platform.YOUTUBE);
            assertThat(platforms.get(0).label()).isEqualTo("YouTube");
            assertThat(platforms.get(0).available()).isTrue();
            assertThat(platforms.get(0).inputHint()).contains("ハンドル");
        }

        @Test
        @DisplayName("正常系：認証情報が未設定のプラットフォームは使えない状態として返す")
        void testMethod02() {
            // 設定漏れに気づくのが「登録ボタンを押した後の失敗」だけにならないようにする
            when(youTubePlatform.platform()).thenReturn(Platform.YOUTUBE);
            when(youTubePlatform.isAvailable()).thenReturn(true);
            when(twitchPlatform.platform()).thenReturn(Platform.TWITCH);
            when(twitchPlatform.isAvailable()).thenReturn(false);
            when(streamPlatformRegistry.all()).thenReturn(List.of(youTubePlatform, twitchPlatform));

            List<PlatformResponse> platforms = controller.listPlatforms();

            assertThat(platforms).hasSize(2);
            // 使えなくても選択肢からは消さない（設定すれば使えることが伝わるように）
            assertThat(platforms.get(1).name()).isEqualTo(Platform.TWITCH);
            assertThat(platforms.get(1).available()).isFalse();
        }
    }
}
