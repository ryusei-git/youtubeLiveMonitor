package com.example.monitor.service;

import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.notification.DiscordNotifier;
import com.example.monitor.platform.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.monitor.service.DetectionFailureAlerter.CHANNEL_ALERT_FAILURES;
import static com.example.monitor.service.DetectionFailureAlerter.PLATFORM_ALERT_CYCLES;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DetectionFailureAlerter")
class DetectionFailureAlerterTest {

    @Mock
    private DiscordNotifier discordNotifier;

    @InjectMocks
    private DetectionFailureAlerter alerter;

    /**
     * 監視対象のチャンネルを作る。連続失敗回数は、巡回の開始時に DB から読んだ値（この巡回の失敗を含まない）を模す。
     */
    private static MonitoredChannel channel(long id, int consecutiveDetectionFailures) {
        MonitoredChannel channel = new MonitoredChannel("UCchannel" + id, "チャンネル" + id);
        channel.setId(id);
        channel.setConsecutiveDetectionFailures(consecutiveDetectionFailures);
        return channel;
    }

    /** 渡したチャンネルをすべて「配信していない」と判定できた応答。含めなかったチャンネルは判定できなかった扱いになる。 */
    private static Map<String, LiveStreamDetection> detected(List<MonitoredChannel> channels) {
        Map<String, LiveStreamDetection> detections = new LinkedHashMap<>();
        for (MonitoredChannel channel : channels) {
            detections.put(channel.getYoutubeChannelId(), LiveStreamDetection.notLive());
        }
        return detections;
    }

    /** 同じ判定結果の巡回を指定した回数くり返す。 */
    private void runCycles(int times, List<MonitoredChannel> channels, Map<String, LiveStreamDetection> detections) {
        for (int i = 0; i < times; i++) {
            alerter.recordPlatformResult(Platform.YOUTUBE, channels, detections);
        }
    }

    @Nested
    @DisplayName("recordPlatformResult()")
    class RecordPlatformResult {

        @Test
        @DisplayName("正常系：過半数が判定できない巡回が続いたら、PLATFORM_ALERT_CYCLES回目に1回だけ知らせる")
        void testMethod01() {
            List<MonitoredChannel> channels = List.of(channel(1L, 0), channel(2L, 0), channel(3L, 0));

            runCycles(PLATFORM_ALERT_CYCLES - 1, channels, Map.of());
            verify(discordNotifier, never()).sendAdminAlert(anyString());

            runCycles(1, channels, Map.of());
            verify(discordNotifier, times(1)).sendAdminAlert(contains("過半数"));

            // 知らせた後も失敗が続いている間は送り直さない
            runCycles(3, channels, Map.of());
            verify(discordNotifier, times(1)).sendAdminAlert(anyString());
        }

        @Test
        @DisplayName("正常系：知らせた後に判定できるようになったら、戻ったことを1回だけ知らせる")
        void testMethod02() {
            List<MonitoredChannel> channels = List.of(channel(1L, 0), channel(2L, 0), channel(3L, 0));

            runCycles(PLATFORM_ALERT_CYCLES, channels, Map.of());
            runCycles(2, channels, detected(channels));

            verify(discordNotifier, times(1)).sendAdminAlert(contains("判定が戻りました"));
            verify(discordNotifier, times(2)).sendAdminAlert(anyString());
        }

        @Test
        @DisplayName("正常系：ちょうど半数が判定できない巡回は過半数に数えない")
        void testMethod03() {
            MonitoredChannel detectedChannel = channel(1L, 0);
            MonitoredChannel missingChannel = channel(2L, 0);
            List<MonitoredChannel> channels = List.of(detectedChannel, missingChannel);

            runCycles(PLATFORM_ALERT_CYCLES + 1, channels, detected(List.of(detectedChannel)));

            verify(discordNotifier, never()).sendAdminAlert(anyString());
        }

        @Test
        @DisplayName("正常系：途中で判定できる巡回を挟むと、続いた回数を数え直す")
        void testMethod04() {
            List<MonitoredChannel> channels = List.of(channel(1L, 0), channel(2L, 0), channel(3L, 0));

            runCycles(PLATFORM_ALERT_CYCLES - 1, channels, Map.of());
            runCycles(1, channels, detected(channels));
            runCycles(PLATFORM_ALERT_CYCLES - 1, channels, Map.of());

            verify(discordNotifier, never()).sendAdminAlert(anyString());
        }

        @Test
        @DisplayName("正常系：1チャンネルの連続失敗がしきい値に届いたら、そのチャンネルを1回だけ知らせる")
        void testMethod05() {
            MonitoredChannel broken = channel(1L, CHANNEL_ALERT_FAILURES - 1);
            MonitoredChannel healthy1 = channel(2L, 0);
            MonitoredChannel healthy2 = channel(3L, 0);
            List<MonitoredChannel> channels = List.of(broken, healthy1, healthy2);
            Map<String, LiveStreamDetection> detections = detected(List.of(healthy1, healthy2));

            runCycles(1, channels, detections);
            verify(discordNotifier, times(1)).sendAdminAlert(contains("UCchannel1"));

            runCycles(1, channels, detections);
            verify(discordNotifier, times(1)).sendAdminAlert(anyString());
        }

        @Test
        @DisplayName("正常系：1チャンネルの連続失敗がしきい値に届かなければ知らせない")
        void testMethod06() {
            MonitoredChannel broken = channel(1L, CHANNEL_ALERT_FAILURES - 2);
            MonitoredChannel healthy1 = channel(2L, 0);
            MonitoredChannel healthy2 = channel(3L, 0);

            runCycles(1, List.of(broken, healthy1, healthy2), detected(List.of(healthy1, healthy2)));

            verify(discordNotifier, never()).sendAdminAlert(anyString());
        }

        @Test
        @DisplayName("正常系：過半数が判定できない巡回では、チャンネル単位の知らせを送らない")
        void testMethod07() {
            List<MonitoredChannel> channels = List.of(
                    channel(1L, CHANNEL_ALERT_FAILURES - 1),
                    channel(2L, CHANNEL_ALERT_FAILURES - 1),
                    channel(3L, CHANNEL_ALERT_FAILURES - 1));

            runCycles(1, channels, Map.of());

            verify(discordNotifier, never()).sendAdminAlert(anyString());
        }

        @Test
        @DisplayName("正常系：知らせたチャンネルが判定できるようになったら、戻ったことを知らせる")
        void testMethod08() {
            MonitoredChannel broken = channel(1L, CHANNEL_ALERT_FAILURES - 1);
            MonitoredChannel healthy1 = channel(2L, 0);
            MonitoredChannel healthy2 = channel(3L, 0);
            List<MonitoredChannel> channels = List.of(broken, healthy1, healthy2);

            runCycles(1, channels, detected(List.of(healthy1, healthy2)));
            runCycles(1, channels, detected(channels));

            verify(discordNotifier, times(1)).sendAdminAlert(contains("判定が戻りました"));
        }

        @Test
        @DisplayName("異常系：送信に失敗しても例外を投げず、同じ失敗について送り直さない")
        void testMethod09() {
            doThrow(new RuntimeException("送信に失敗")).when(discordNotifier).sendAdminAlert(anyString());
            List<MonitoredChannel> channels = List.of(channel(1L, 0), channel(2L, 0), channel(3L, 0));

            assertThatCode(() -> runCycles(PLATFORM_ALERT_CYCLES + 2, channels, Map.of()))
                    .doesNotThrowAnyException();

            verify(discordNotifier, times(1)).sendAdminAlert(anyString());
        }

        @Test
        @DisplayName("正常系：過半数が判定できなかった巡回の直後は、積み上がった連続失敗でもチャンネル単位の知らせを送らない")
        void testMethod10() {
            // 1 時間ほど続いた全体の障害で、どのチャンネルも連続失敗がしきい値の手前まで積み上がっている
            MonitoredChannel queriedBeforeRecovery = channel(1L, CHANNEL_ALERT_FAILURES - 1);
            MonitoredChannel queriedAfterRecovery1 = channel(2L, CHANNEL_ALERT_FAILURES - 1);
            MonitoredChannel queriedAfterRecovery2 = channel(3L, CHANNEL_ALERT_FAILURES - 1);
            List<MonitoredChannel> channels =
                    List.of(queriedBeforeRecovery, queriedAfterRecovery1, queriedAfterRecovery2);

            // 障害の最後の巡回：過半数が判定できない
            runCycles(1, channels, Map.of());
            // 障害が巡回の途中で直った：直る前に問い合わせた 1 件だけが判定できない
            runCycles(1, channels, detected(List.of(queriedAfterRecovery1, queriedAfterRecovery2)));
            // 次の巡回ではすべて判定できる
            runCycles(1, channels, detected(channels));

            verify(discordNotifier, never()).sendAdminAlert(anyString());
        }

        @Test
        @DisplayName("正常系：直後の巡回で見送ったチャンネルも、その次の巡回でまだ判定できなければ1回だけ知らせる")
        void testMethod11() {
            MonitoredChannel broken = channel(1L, CHANNEL_ALERT_FAILURES - 1);
            MonitoredChannel healthy1 = channel(2L, CHANNEL_ALERT_FAILURES - 1);
            MonitoredChannel healthy2 = channel(3L, CHANNEL_ALERT_FAILURES - 1);
            List<MonitoredChannel> channels = List.of(broken, healthy1, healthy2);
            Map<String, LiveStreamDetection> onlyBrokenFails = detected(List.of(healthy1, healthy2));

            runCycles(1, channels, Map.of());
            runCycles(1, channels, onlyBrokenFails);
            verify(discordNotifier, never()).sendAdminAlert(anyString());

            runCycles(1, channels, onlyBrokenFails);
            verify(discordNotifier, times(1)).sendAdminAlert(contains("UCchannel1"));
            verify(discordNotifier, times(1)).sendAdminAlert(anyString());
        }
    }
}
