package com.example.monitor.service;
import com.example.monitor.entity.VideoCollectionQuota;
import com.example.monitor.repository.VideoCollectionQuotaRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.io.IOException;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class YouTubeCatalogQuotaTest {
    @Mock VideoCollectionQuotaRepository repository;
    @InjectMocks YouTubeCatalogQuota quota;
    @Nested class Acquire {
        @Test @DisplayName("異常系：永続化した日次上限に達したらAPIを追加で呼べない")
        void testMethod01() {
            var state = new VideoCollectionQuota(); state.setId("youtube-library");
            state.setQuotaDate(LocalDate.now(ZoneId.of("America/Los_Angeles"))); state.setRequests(3000);
            when(repository.findById("youtube-library")).thenReturn(Optional.of(state));
            assertThatThrownBy(quota::acquire).isInstanceOf(IOException.class);
            verify(repository, never()).saveAndFlush(any());
        }
        @Test @DisplayName("正常系：太平洋時間で日が変われば収集予算を更新する")
        void testMethod02() throws Exception {
            var state = new VideoCollectionQuota(); state.setQuotaDate(LocalDate.now(ZoneId.of("America/Los_Angeles")).minusDays(1)); state.setRequests(3000);
            when(repository.findById("youtube-library")).thenReturn(Optional.of(state));
            quota.acquire(); assertThat(state.getRequests()).isEqualTo(1);
            verify(repository).saveAndFlush(state);
        }
    }
}
