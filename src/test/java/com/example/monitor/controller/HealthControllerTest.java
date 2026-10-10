package com.example.monitor.controller;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.controller.HealthController.HealthResponse;
import com.example.monitor.service.ActiveVideoJobs;
import com.example.monitor.service.PollingStatusTracker;
import com.example.monitor.service.UptimeTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 外の見張り（{@code bin/health-watch.sh}・{@code bin/service.sh status}）が頼る巡回の生存の判定を確かめる。
 *
 * <p>{@code HealthController} は中で {@code LocalDateTime.now()} を呼ぶので、しきい値ちょうどは確かめず、
 * 手前は 5 秒・先は 2 秒の余裕をとる（テストの実行に 5 秒かからない限り結果が変わらない）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HealthController")
class HealthControllerTest {

    @Mock
    private PollingStatusTracker pollingStatusTracker;

    @Mock
    private UptimeTracker uptimeTracker;

    /** 本物を使う（予約の集合を持つだけで、モックにすると hasAny() の既定値 false しか確かめられない） */
    private final ActiveVideoJobs activeVideoJobs = new ActiveVideoJobs();

    private HealthController newController(int intervalSeconds) {
        MonitorProperties properties = new MonitorProperties(new YouTubeProperties("", intervalSeconds),
                new TwitchProperties("", ""), new DiscordProperties(""),
                new RecordingProperties("recordings", 1080), new MonitorProperties.AdminProperties("admin", ""));
        return new HealthController(pollingStatusTracker, uptimeTracker, properties, activeVideoJobs);
    }

    @Nested
    @DisplayName("health()")
    class Health {

        @Test
        @DisplayName("正常系：最後の巡回が 30 秒前なら 200・UP と経過秒を返し、予約が無ければ録画中ではない")
        void testMethod01() {
            HealthController controller = newController(120);
            when(pollingStatusTracker.lastSucceededAt()).thenReturn(LocalDateTime.now().minusSeconds(30));

            ResponseEntity<HealthResponse> response = controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo("UP");
            assertThat(response.getBody().secondsSinceLastPoll()).isBetween(30L, 31L);
            assertThat(response.getBody().recording()).isFalse();
        }

        @Test
        @DisplayName("正常系：最後の巡回がしきい値（間隔の 3 倍）の手前なら UP のまま")
        void testMethod02() {
            HealthController controller = newController(120);
            when(pollingStatusTracker.lastSucceededAt()).thenReturn(LocalDateTime.now().minusSeconds(355));

            ResponseEntity<HealthResponse> response = controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo("UP");
        }

        @Test
        @DisplayName("異常系：最後の巡回からしきい値を過ぎたら 503・STALE と経過秒を返し、録画中なら 503 の本文でもそう示す")
        void testMethod03() {
            HealthController controller = newController(120);
            when(pollingStatusTracker.lastSucceededAt()).thenReturn(LocalDateTime.now().minusSeconds(362));
            activeVideoJobs.reserve("video-1");

            ResponseEntity<HealthResponse> response = controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo("STALE");
            assertThat(response.getBody().secondsSinceLastPoll()).isBetween(362L, 363L);
            // 運用側の guard は 503 でも本文を読み、録画中なら理由として出す
            assertThat(response.getBody().recording()).isTrue();
        }

        @Test
        @DisplayName("正常系：まだ 1 巡もしていなくても、起動から 10 秒なら 200・STARTING で経過秒は null")
        void testMethod04() {
            HealthController controller = newController(120);
            when(pollingStatusTracker.lastSucceededAt()).thenReturn(null);
            when(uptimeTracker.getStartedAt()).thenReturn(LocalDateTime.now().minusSeconds(10));

            ResponseEntity<HealthResponse> response = controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo("STARTING");
            assertThat(response.getBody().secondsSinceLastPoll()).isNull();
        }

        @Test
        @DisplayName("異常系：1 巡もしないまま起動からしきい値を過ぎたら 503・STALE で経過秒は null（起動直後から固まっている）")
        void testMethod05() {
            HealthController controller = newController(120);
            when(pollingStatusTracker.lastSucceededAt()).thenReturn(null);
            when(uptimeTracker.getStartedAt()).thenReturn(LocalDateTime.now().minusSeconds(362));

            ResponseEntity<HealthResponse> response = controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo("STALE");
            assertThat(response.getBody().secondsSinceLastPoll()).isNull();
        }

        @Test
        @DisplayName("正常系：巡回を止めた起動は、最後の巡回が古くても 200・DISABLED を返し、起動時刻を見ない")
        void testMethod06() {
            HealthController controller = newController(120);
            ReflectionTestUtils.setField(controller, "schedulingEnabled", false);
            when(pollingStatusTracker.lastSucceededAt()).thenReturn(LocalDateTime.now().minusSeconds(100000));

            ResponseEntity<HealthResponse> response = controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo("DISABLED");
            assertThat(response.getBody().secondsSinceLastPoll()).isBetween(100000L, 100001L);
            verifyNoInteractions(uptimeTracker);
        }

        @Test
        @DisplayName("正常系：巡回を止めた起動で 1 巡もしていなければ DISABLED で経過秒は null")
        void testMethod07() {
            HealthController controller = newController(120);
            ReflectionTestUtils.setField(controller, "schedulingEnabled", false);
            when(pollingStatusTracker.lastSucceededAt()).thenReturn(null);

            ResponseEntity<HealthResponse> response = controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo("DISABLED");
            assertThat(response.getBody().secondsSinceLastPoll()).isNull();
        }

        @Test
        @DisplayName("異常系：しきい値は巡回の間隔から決まる（間隔 60 秒なら 190 秒前で STALE）")
        void testMethod08() {
            // 間隔 120 秒（しきい値 360 秒）なら UP になる経過
            HealthController controller = newController(60);
            when(pollingStatusTracker.lastSucceededAt()).thenReturn(LocalDateTime.now().minusSeconds(190));

            ResponseEntity<HealthResponse> response = controller.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo("STALE");
        }
    }

    @Nested
    @DisplayName("HealthResponse")
    class HealthResponseFields {

        @Test
        @DisplayName("正常系：応答の項目は status・secondsSinceLastPoll・recording の 3 つだけ（ログイン無しで読め、bin/service.sh と運用側の guard がこの名前で読むため）")
        void testMethod01() {
            List<String> names = Arrays.stream(HealthResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(names).containsExactly("status", "secondsSinceLastPoll", "recording");
        }
    }
}
