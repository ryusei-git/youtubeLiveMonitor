package com.example.monitor.repository;

import com.example.monitor.entity.MonitoredChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 巡回の決まり（判定できなかったときは配信状態に触れない・通知に成功したら失敗回数を戻す、など）は、
 * JPQL の SET 句の中にしか書かれていない。巡回のテストはこのリポジトリをモックにして呼び出しだけを見るので、
 * SET 句から 1 行消えても気付けない。{@link UserNotificationRepositoryTest} と同じく {@code @DataJpaTest} で
 * インメモリ H2 に実際に UPDATE を流し、変わるべき列と変わってはならない列の両方を確かめる。
 */
@DataJpaTest
@DisplayName("MonitoredChannelRepository")
class MonitoredChannelRepositoryTest {

    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 9, 27, 11, 0);
    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 9, 27, 12, 0);

    @Autowired
    private MonitoredChannelRepository monitoredChannelRepository;

    @Autowired
    private TestEntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** 事前の状態はセッターで入れてから渡すこと（保存後に変えると検証の前提が崩れる）。 */
    private MonitoredChannel persist(MonitoredChannel channel) {
        return monitoredChannelRepository.saveAndFlush(channel);
    }

    private MonitoredChannel newChannel() {
        return new MonitoredChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
    }

    private MonitoredChannel reload(MonitoredChannel channel) {
        flushAndClear();
        return monitoredChannelRepository.findById(channel.getId()).orElseThrow();
    }

    @Nested
    @DisplayName("updateObservedLiveState()")
    class UpdateObservedLiveState {

        @Test
        @DisplayName("正常系：配信中の観測を書き、連続失敗回数を 0 に戻し、判定成功時刻を監視時刻にする")
        void testMethod01() {
            MonitoredChannel channel = newChannel();
            channel.setCurrentlyLive(false);
            channel.setConsecutiveDetectionFailures(3);
            channel.setLastDetectionSuccessAt(EARLIER);
            MonitoredChannel saved = persist(channel);

            int updated = monitoredChannelRepository.updateObservedLiveState(saved.getId(), true, "live1", CHECKED_AT);

            MonitoredChannel reloaded = reload(saved);
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.isCurrentlyLive()).isTrue();
            assertThat(reloaded.getCurrentLiveVideoId()).isEqualTo("live1");
            assertThat(reloaded.getLastCheckedAt()).isEqualTo(CHECKED_AT);
            assertThat(reloaded.getLastDetectionSuccessAt()).isEqualTo(CHECKED_AT);
            assertThat(reloaded.getConsecutiveDetectionFailures()).isZero();
        }

        @Test
        @DisplayName("正常系：配信していない観測では配信中の動画 ID を消し、配信予定・通知・録画の列とチャンネル名には触れない")
        void testMethod02() {
            MonitoredChannel channel = newChannel();
            channel.setCurrentlyLive(true);
            channel.setCurrentLiveVideoId("live1");
            channel.setUpcomingVideoId("up1");
            channel.setUpcomingTitle("予定");
            channel.setLastNotifiedVideoId("old");
            channel.setNotificationFailureCount(2);
            channel.setLastRecordedVideoId("rec1");
            MonitoredChannel saved = persist(channel);

            monitoredChannelRepository.updateObservedLiveState(saved.getId(), false, null, CHECKED_AT);

            MonitoredChannel reloaded = reload(saved);
            assertThat(reloaded.isCurrentlyLive()).isFalse();
            assertThat(reloaded.getCurrentLiveVideoId()).isNull();
            assertThat(reloaded.getChannelName()).isEqualTo("Channel A");
            assertThat(reloaded.getUpcomingVideoId()).isEqualTo("up1");
            assertThat(reloaded.getUpcomingTitle()).isEqualTo("予定");
            assertThat(reloaded.getLastNotifiedVideoId()).isEqualTo("old");
            assertThat(reloaded.getNotificationFailureCount()).isEqualTo(2);
            assertThat(reloaded.getLastRecordedVideoId()).isEqualTo("rec1");
        }

        @Test
        @DisplayName("異常系：対象の行が無ければ 0 を返す")
        void testMethod03() {
            int updated = monitoredChannelRepository.updateObservedLiveState(Long.MAX_VALUE, true, "live1", CHECKED_AT);

            assertThat(updated).isZero();
        }
    }

    @Nested
    @DisplayName("recordDetectionFailure()")
    class RecordDetectionFailure {

        @Test
        @DisplayName("正常系：連続失敗回数を 1 増やして監視時刻を書き、配信状態と判定成功時刻には触れない")
        void testMethod01() {
            MonitoredChannel channel = newChannel();
            channel.setCurrentlyLive(true);
            channel.setCurrentLiveVideoId("live1");
            channel.setLastCheckedAt(EARLIER);
            channel.setLastDetectionSuccessAt(EARLIER);
            channel.setConsecutiveDetectionFailures(1);
            MonitoredChannel saved = persist(channel);

            int updated = monitoredChannelRepository.recordDetectionFailure(saved.getId(), CHECKED_AT);

            MonitoredChannel reloaded = reload(saved);
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getConsecutiveDetectionFailures()).isEqualTo(2);
            assertThat(reloaded.getLastCheckedAt()).isEqualTo(CHECKED_AT);
            assertThat(reloaded.isCurrentlyLive()).isTrue();
            assertThat(reloaded.getCurrentLiveVideoId()).isEqualTo("live1");
            assertThat(reloaded.getLastDetectionSuccessAt()).isEqualTo(EARLIER);
        }

        @Test
        @DisplayName("正常系：配信予定と通知の列には触れない")
        void testMethod02() {
            MonitoredChannel channel = newChannel();
            channel.setUpcomingVideoId("up1");
            channel.setUpcomingTitle("予定");
            channel.setUpcomingScheduledStartTime(EARLIER);
            channel.setLastNotifiedVideoId("old");
            channel.setNotificationFailureCount(2);
            MonitoredChannel saved = persist(channel);

            monitoredChannelRepository.recordDetectionFailure(saved.getId(), CHECKED_AT);

            MonitoredChannel reloaded = reload(saved);
            assertThat(reloaded.getUpcomingVideoId()).isEqualTo("up1");
            assertThat(reloaded.getUpcomingTitle()).isEqualTo("予定");
            assertThat(reloaded.getUpcomingScheduledStartTime()).isEqualTo(EARLIER);
            assertThat(reloaded.getLastNotifiedVideoId()).isEqualTo("old");
            assertThat(reloaded.getNotificationFailureCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系：登録直後の状態から 2 回続けて失敗すると連続失敗回数が 2 になる")
        void testMethod03() {
            MonitoredChannel saved = persist(newChannel());

            monitoredChannelRepository.recordDetectionFailure(saved.getId(), EARLIER);
            monitoredChannelRepository.recordDetectionFailure(saved.getId(), CHECKED_AT);

            assertThat(reload(saved).getConsecutiveDetectionFailures()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("updateUpcoming()")
    class UpdateUpcoming {

        @Test
        @DisplayName("正常系：配信予定の 3 列を上書きし、配信状態には触れない")
        void testMethod01() {
            MonitoredChannel channel = newChannel();
            channel.setUpcomingVideoId("up1");
            channel.setUpcomingTitle("古い予定");
            channel.setUpcomingScheduledStartTime(EARLIER);
            channel.setCurrentlyLive(true);
            channel.setCurrentLiveVideoId("live1");
            MonitoredChannel saved = persist(channel);

            int updated = monitoredChannelRepository.updateUpcoming(saved.getId(), "up2", "新しい予定", CHECKED_AT);

            MonitoredChannel reloaded = reload(saved);
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getUpcomingVideoId()).isEqualTo("up2");
            assertThat(reloaded.getUpcomingTitle()).isEqualTo("新しい予定");
            assertThat(reloaded.getUpcomingScheduledStartTime()).isEqualTo(CHECKED_AT);
            assertThat(reloaded.isCurrentlyLive()).isTrue();
            assertThat(reloaded.getCurrentLiveVideoId()).isEqualTo("live1");
        }
    }

    @Nested
    @DisplayName("clearUpcoming()")
    class ClearUpcoming {

        @Test
        @DisplayName("正常系：配信予定の 3 列を null にし、配信状態には触れない")
        void testMethod01() {
            MonitoredChannel channel = newChannel();
            channel.setUpcomingVideoId("up1");
            channel.setUpcomingTitle("予定");
            channel.setUpcomingScheduledStartTime(EARLIER);
            channel.setCurrentlyLive(true);
            channel.setCurrentLiveVideoId("live1");
            MonitoredChannel saved = persist(channel);

            int updated = monitoredChannelRepository.clearUpcoming(saved.getId());

            MonitoredChannel reloaded = reload(saved);
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getUpcomingVideoId()).isNull();
            assertThat(reloaded.getUpcomingTitle()).isNull();
            assertThat(reloaded.getUpcomingScheduledStartTime()).isNull();
            assertThat(reloaded.isCurrentlyLive()).isTrue();
            assertThat(reloaded.getCurrentLiveVideoId()).isEqualTo("live1");
        }
    }

    @Nested
    @DisplayName("updateLastNotifiedVideoId()")
    class UpdateLastNotifiedVideoId {

        @Test
        @DisplayName("正常系：通知済みの動画 ID を書き、通知失敗回数を 0 に戻す")
        void testMethod01() {
            MonitoredChannel channel = newChannel();
            channel.setLastNotifiedVideoId("old");
            channel.setNotificationFailureCount(2);
            MonitoredChannel saved = persist(channel);

            int updated = monitoredChannelRepository.updateLastNotifiedVideoId(saved.getId(), "new");

            MonitoredChannel reloaded = reload(saved);
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getLastNotifiedVideoId()).isEqualTo("new");
            assertThat(reloaded.getNotificationFailureCount()).isZero();
        }
    }

    @Nested
    @DisplayName("incrementNotificationFailureCount()")
    class IncrementNotificationFailureCount {

        @Test
        @DisplayName("正常系：通知失敗回数だけを 1 増やし、通知済みの動画 ID は変えない")
        void testMethod01() {
            MonitoredChannel channel = newChannel();
            channel.setLastNotifiedVideoId("old");
            channel.setNotificationFailureCount(1);
            MonitoredChannel saved = persist(channel);

            int updated = monitoredChannelRepository.incrementNotificationFailureCount(saved.getId());

            MonitoredChannel reloaded = reload(saved);
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getNotificationFailureCount()).isEqualTo(2);
            assertThat(reloaded.getLastNotifiedVideoId()).isEqualTo("old");
        }
    }

    @Nested
    @DisplayName("resetNotificationFailureCount()")
    class ResetNotificationFailureCount {

        @Test
        @DisplayName("正常系：通知失敗回数を 0 に戻し、通知済みの動画 ID は変えない")
        void testMethod01() {
            MonitoredChannel channel = newChannel();
            channel.setLastNotifiedVideoId("old");
            channel.setNotificationFailureCount(3);
            MonitoredChannel saved = persist(channel);

            int updated = monitoredChannelRepository.resetNotificationFailureCount(saved.getId());

            MonitoredChannel reloaded = reload(saved);
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getNotificationFailureCount()).isZero();
            assertThat(reloaded.getLastNotifiedVideoId()).isEqualTo("old");
        }
    }
}
