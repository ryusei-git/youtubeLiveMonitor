package com.example.monitor.platform;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.VideoSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StreamPlatform#detectLiveStreams(List)} の既定実装（並行取得）のテスト。
 *
 * <p>1 件ずつ順番に取りに行くと、配信者が増えるほど巡回時間が線形に伸びる
 * （実測: YouTube 18 チャンネルで 8.2 秒）。待っているだけの時間なので仮想スレッドで
 * 重ねているが、<b>結果の取りこぼしと同時接続数の暴走</b>という新しい壊れ方が生まれる。
 * ここはその 2 つを見張る。
 *
 * <p>Twitch のように一括問い合わせができるプラットフォームは自分で上書きするため、
 * ここで見るのは<b>上書きしない側（YouTube）が使う既定実装</b>。
 */
@DisplayName("StreamPlatform")
class StreamPlatformTest {

    /**
     * 検知だけを差し替えられるテスト用の実装。
     *
     * <p>{@link StreamPlatform} は既定実装を持つインターフェースなので、
     * 既定実装そのものを試すには実装クラスが要る。検知以外は使わないため最小限にしてある。
     */
    private static class TestPlatform implements StreamPlatform {

        /** 同時に走っている検知の数。 */
        private final AtomicInteger running = new AtomicInteger();

        /** 観測した同時実行数の最大値。 */
        private final AtomicInteger peak = new AtomicInteger();

        /** 検知を実行したスレッド。1 件のときに別スレッドを起こしていないかを見る。 */
        private final Set<String> threads = ConcurrentHashMap.newKeySet();

        /** この ID が来たら例外を投げる。 */
        private final String failingChannelId;

        TestPlatform(String failingChannelId) {
            this.failingChannelId = failingChannelId;
        }

        @Override
        public LiveStreamDetection detectLiveStream(String channelId) {
            threads.add(Thread.currentThread().getName());
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                // 同時実行数を観測できるよう、少しだけ重なりを作る
                Thread.sleep(20);
                if (channelId.equals(failingChannelId)) {
                    throw new IllegalStateException("検知に失敗しました");
                }
                return LiveStreamDetection.live(channelId + "-video", "配信", null, "https://example.test");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LiveStreamDetection.failed();
            } finally {
                running.decrementAndGet();
            }
        }

        @Override
        public Platform platform() {
            return Platform.YOUTUBE;
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
            return false;
        }

        @Override
        public Optional<String> resolveChannelId(VideoSource source) {
            return Optional.empty();
        }

        @Override
        public Optional<LiveStreamDetails> fetchDetails(String videoId) {
            return Optional.empty();
        }
    }

    /**
     * 連番のチャンネル ID を作る。
     *
     * @param count 件数
     * @return チャンネル ID の一覧
     */
    private static List<String> channelIds(int count) {
        return IntStream.range(0, count).mapToObj(i -> "channel" + i).toList();
    }

    @Nested
    @DisplayName("detectLiveStreams()")
    class DetectLiveStreams {

        @Test
        @DisplayName("正常系：全チャンネルの結果が漏れなく返る")
        void testMethod01() {
            // 取りこぼすと、呼び出し側からは「まだ調べていない」のと区別が付かなくなる
            TestPlatform platform = new TestPlatform(null);
            List<String> ids = channelIds(20);

            Map<String, LiveStreamDetection> results = platform.detectLiveStreams(ids);

            assertThat(results).hasSize(20);
            assertThat(results.keySet()).containsExactlyElementsOf(ids);
            assertThat(results.values()).allMatch(LiveStreamDetection::isLive);
        }

        @Test
        @DisplayName("正常系：同時に走る問い合わせが上限を超えない")
        void testMethod02() {
            // 無制限に並べると、短時間に大量のアクセスを送ることになり相手側から遮断されうる
            TestPlatform platform = new TestPlatform(null);

            platform.detectLiveStreams(channelIds(30));

            assertThat(platform.peak.get()).isLessThanOrEqualTo(StreamPlatform.MAX_CONCURRENT_DETECTIONS);
        }

        @Test
        @DisplayName("異常系：1件が例外を投げても結果から消えず判定失敗として返る")
        void testMethod03() {
            // 結果から落とすと「配信していない」とも「まだ調べていない」とも区別が付かない
            TestPlatform platform = new TestPlatform("channel3");

            Map<String, LiveStreamDetection> results = platform.detectLiveStreams(channelIds(5));

            assertThat(results).hasSize(5);
            assertThat(results.get("channel3").isDetectionFailed()).isTrue();
            assertThat(results.get("channel0").isLive()).isTrue();
        }

        @Test
        @DisplayName("正常系：1件だけのときはスレッドを起こさず呼び出し元で実行する")
        void testMethod04() {
            // 1 件のためにスレッドを起こすほうが高くつく
            TestPlatform platform = new TestPlatform(null);
            String callerThread = Thread.currentThread().getName();

            Map<String, LiveStreamDetection> results = platform.detectLiveStreams(List.of("channel0"));

            assertThat(results).hasSize(1);
            assertThat(platform.threads).containsExactly(callerThread);
        }

        @Test
        @DisplayName("正常系：空のリストを渡しても落ちず空を返す")
        void testMethod05() {
            TestPlatform platform = new TestPlatform(null);

            assertThat(platform.detectLiveStreams(new ArrayList<>())).isEmpty();
        }
    }
}
