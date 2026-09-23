package com.example.monitor.platform;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.VideoSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StreamPlatformRegistry")
class StreamPlatformRegistryTest {

    /**
     * 特定のドメインだけを担当する、テスト用の最小限の実装。
     *
     * <p>実装クラス（{@code YouTubeStreamPlatform} 等）をモックにすると、この登録簿が
     * 「各実装に判定を任せている」ことではなく「モックの設定どおりに動く」ことの確認になってしまうため、
     * 素の実装を用意している。
     */
    private static final class FakePlatform implements StreamPlatform {

        private final Platform platform;
        private final String domain;

        private FakePlatform(Platform platform, String domain) {
            this.platform = platform;
            this.domain = domain;
        }

        @Override
        public Platform platform() {
            return platform;
        }

        @Override
        public String inputHint() {
            return "";
        }

        @Override
        public String normalizeChannelInput(String rawInput) {
            return rawInput;
        }

        @Override
        public boolean supportsUrl(String url) {
            return url != null && url.contains(domain);
        }

        @Override
        public Optional<String> resolveChannelId(VideoSource source) {
            return Optional.empty();
        }

        @Override
        public LiveStreamDetection detectLiveStream(String channelId) {
            return LiveStreamDetection.notLive();
        }

        @Override
        public Optional<LiveStreamDetails> fetchDetails(String videoId) {
            return Optional.empty();
        }
    }

    private StreamPlatformRegistry newRegistry() {
        return new StreamPlatformRegistry(List.of(
                new FakePlatform(Platform.YOUTUBE, "youtube.com"),
                new FakePlatform(Platform.TWITCH, "twitch.tv")));
    }

    @Nested
    @DisplayName("findByUrl()")
    class FindByUrl {

        @Test
        @DisplayName("正常系：URLを担当するプラットフォームを返す")
        void testMethod01() {
            StreamPlatformRegistry registry = newRegistry();

            assertThat(registry.findByUrl("https://www.youtube.com/watch?v=abc").platform())
                    .isEqualTo(Platform.YOUTUBE);
            assertThat(registry.findByUrl("https://www.twitch.tv/videos/123").platform())
                    .isEqualTo(Platform.TWITCH);
        }

        @Test
        @DisplayName("異常系：どのプラットフォームも担当しないURLは対応状況が分かる例外を投げる")
        void testMethod02() {
            assertThatThrownBy(() -> newRegistry().findByUrl("https://example.com/video/1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    // 何に対応しているかが分からないと、利用者は直しようがない
                    .hasMessageContaining("YouTube")
                    .hasMessageContaining("Twitch")
                    .hasMessageContaining("https://example.com/video/1");
        }
    }

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("正常系：プラットフォームに対応する実装を返す")
        void testMethod01() {
            assertThat(newRegistry().get(Platform.TWITCH).platform()).isEqualTo(Platform.TWITCH);
        }

        @Test
        @DisplayName("異常系：対応する実装が無い場合は例外を投げる")
        void testMethod02() {
            StreamPlatformRegistry registry =
                    new StreamPlatformRegistry(List.of(new FakePlatform(Platform.YOUTUBE, "youtube.com")));

            assertThatThrownBy(() -> registry.get(Platform.TWITCH))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("all()")
    class All {

        @Test
        @DisplayName("正常系：登録された実装をすべて返す")
        void testMethod01() {
            assertThat(newRegistry().all()).hasSize(2);
        }

        @Test
        @DisplayName("異常系：同じプラットフォームの実装が重複している場合は起動時に例外を投げる")
        void testMethod02() {
            List<StreamPlatform> duplicated = List.of(
                    new FakePlatform(Platform.YOUTUBE, "youtube.com"),
                    new FakePlatform(Platform.YOUTUBE, "youtu.be"));

            assertThatThrownBy(() -> new StreamPlatformRegistry(duplicated))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
