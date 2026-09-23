package com.example.monitor.controller;

import com.example.monitor.dto.ManualCheckResponse;
import com.example.monitor.exception.MonitoringInProgressException;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.scheduler.LiveStreamPollingScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonitoringController")
class MonitoringControllerTest {

    @Mock
    private LiveStreamPollingScheduler liveStreamPollingScheduler;

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @InjectMocks
    private MonitoringController controller;

    @Nested
    @DisplayName("checkNow()")
    class CheckNow {

        @Test
        @DisplayName("正常系：巡回が実行された場合はチェックしたチャンネル数を返す")
        void testMethod01() {
            when(monitoredChannelRepository.count()).thenReturn(3L);
            when(liveStreamPollingScheduler.pollNow()).thenReturn(true);

            ManualCheckResponse response = controller.checkNow();

            assertThat(response.checkedChannels()).isEqualTo(3L);
        }

        @Test
        @DisplayName("異常系：既に巡回中の場合はMonitoringInProgressExceptionが発生する")
        void testMethod02() {
            // 例外が投げられる経路では件数は使われないため lenient にする
            lenient().when(monitoredChannelRepository.count()).thenReturn(3L);
            when(liveStreamPollingScheduler.pollNow()).thenReturn(false);

            assertThatThrownBy(() -> controller.checkNow())
                    .isInstanceOf(MonitoringInProgressException.class);
        }
    }
}
