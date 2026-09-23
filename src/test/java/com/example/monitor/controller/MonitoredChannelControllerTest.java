package com.example.monitor.controller;

import com.example.monitor.dto.ChannelRegistrationRequest;
import com.example.monitor.dto.ChannelSearchResult;
import com.example.monitor.dto.MonitoredChannelResponse;
import com.example.monitor.dto.RecordTitleFilterRequest;
import com.example.monitor.dto.RecordToggleRequest;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.Platform;
import com.example.monitor.service.MonitoredChannelService;
import com.example.monitor.service.YouTubeApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonitoredChannelController")
class MonitoredChannelControllerTest {

    @Mock
    private MonitoredChannelService monitoredChannelService;

    @Mock
    private YouTubeApiClient youTubeApiClient;

    @InjectMocks
    private MonitoredChannelController controller;

    @Nested
    @DisplayName("listChannels()")
    class ListChannels {

        @Test
        @DisplayName("正常系：登録済みチャンネルをレスポンス形式に変換して返す")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setId(1L);
            when(monitoredChannelService.findAll()).thenReturn(List.of(channel));

            List<MonitoredChannelResponse> result = controller.listChannels();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).youtubeChannelId()).isEqualTo("UCxxxxxxxx");
        }

        @Test
        @DisplayName("正常系：登録が無い場合は空リストを返す")
        void testMethod02() {
            when(monitoredChannelService.findAll()).thenReturn(List.of());

            List<MonitoredChannelResponse> result = controller.listChannels();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("addChannel()")
    class AddChannel {

        @Test
        @DisplayName("正常系：登録に成功した場合は201 CreatedでレスポンスDTOを返す")
        void testMethod01() {
            MonitoredChannel registered = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", true);
            registered.setId(1L);
            when(monitoredChannelService.register(Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", true, null)).thenReturn(registered);

            ResponseEntity<MonitoredChannelResponse> response =
                    controller.addChannel(new ChannelRegistrationRequest(Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", true, null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().youtubeChannelId()).isEqualTo("UCxxxxxxxx");
            assertThat(response.getBody().recordEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("setRecordEnabled()")
    class SetRecordEnabled {

        @Test
        @DisplayName("正常系：切り替えに成功した場合は204 No Contentを返す")
        void testMethod01() {
            ResponseEntity<Void> response = controller.setRecordEnabled(1L, new RecordToggleRequest(true));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(monitoredChannelService, times(1)).setRecordEnabled(1L, true);
        }
    }

    @Nested
    @DisplayName("setRecordTitleKeywords()")
    class SetRecordTitleKeywords {

        @Test
        @DisplayName("正常系：更新に成功した場合は204 No Contentを返す")
        void testMethod01() {
            ResponseEntity<Void> response =
                    controller.setRecordTitleKeywords(1L, new RecordTitleFilterRequest("【ASMR】,【生配信】"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(monitoredChannelService, times(1)).setRecordTitleKeywords(1L, "【ASMR】,【生配信】");
        }
    }

    @Nested
    @DisplayName("removeChannel()")
    class RemoveChannel {

        @Test
        @DisplayName("正常系：削除に成功した場合は204 No Contentを返す")
        void testMethod01() {
            ResponseEntity<Void> response = controller.removeChannel(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(monitoredChannelService, times(1)).remove(1L);
        }
    }

    @Nested
    @DisplayName("searchChannelsByName()")
    class SearchChannelsByName {

        @Test
        @DisplayName("正常系：YouTubeApiClientの検索結果をそのまま返す")
        void testMethod01() {
            ChannelSearchResult searchResult = new ChannelSearchResult("UCxxxxxxxx", "テストチャンネル", "https://example.com/icon.jpg");
            when(youTubeApiClient.searchChannelsByName("テスト")).thenReturn(List.of(searchResult));

            List<ChannelSearchResult> result = controller.searchChannelsByName("テスト");

            assertThat(result).containsExactly(searchResult);
        }
    }
}
