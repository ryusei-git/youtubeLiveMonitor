package com.example.monitor.controller;

import com.example.monitor.dto.DashboardResponse;
import com.example.monitor.dto.DashboardResponse.RecordingStatusSummary;
import com.example.monitor.service.DashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardController")
class DashboardControllerTest {

    @Mock
    private DashboardService dashboardService;

    @InjectMocks
    private DashboardController controller;

    @Nested
    @DisplayName("getDashboard()")
    class GetDashboard {

        @Test
        @DisplayName("正常系：DashboardServiceの集計結果をそのまま返す")
        void testMethod01() {
            DashboardResponse response = new DashboardResponse(
                    1, 1, List.of(), 0, 0, List.of(),
                    new RecordingStatusSummary(0, 0, 0, 0), LocalDateTime.now(), 100L);
            when(dashboardService.getSnapshot()).thenReturn(response);

            DashboardResponse result = controller.getDashboard();

            assertThat(result).isSameAs(response);
        }
    }
}
