package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.ChannelSearchResult;
import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.exception.YouTubeApiUnavailableException;
import com.google.api.client.util.DateTime;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelListResponse;
import com.google.api.services.youtube.model.ChannelSnippet;
import com.google.api.services.youtube.model.ResourceId;
import com.google.api.services.youtube.model.SearchListResponse;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.SearchResultSnippet;
import com.google.api.services.youtube.model.Thumbnail;
import com.google.api.services.youtube.model.ThumbnailDetails;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoLiveStreamingDetails;
import com.google.api.services.youtube.model.VideoListResponse;
import com.google.api.services.youtube.model.VideoSnippet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("YouTubeApiClient")
class YouTubeApiClientTest {

    @Mock
    private YouTube youtube;

    @Mock
    private YouTube.Videos videosResource;

    @Mock
    private YouTube.Videos.List videosListRequest;

    @Mock
    private YouTube.Search searchResource;

    @Mock
    private YouTube.Search.List searchListRequest;

    @Mock
    private YouTube.Channels channelsResource;

    @Mock
    private YouTube.Channels.List channelsListRequest;

    @Mock
    private YouTubeSearchBudget searchBudget;

    private YouTubeApiClient youTubeApiClient;

    @BeforeEach
    void setUp() {
        youTubeApiClient = new YouTubeApiClient(youtube, searchBudget, new MonitorProperties(
                new MonitorProperties.YouTubeProperties("test-key", 120), null, null, null, null));
    }

    private void stubVideosList(VideoListResponse response) throws IOException {
        when(youtube.videos()).thenReturn(videosResource);
        when(videosResource.list(anyList())).thenReturn(videosListRequest);
        when(videosListRequest.setId(anyList())).thenReturn(videosListRequest);
        when(videosListRequest.execute()).thenReturn(response);
    }

    private void stubChannelsList() throws IOException {
        when(youtube.channels()).thenReturn(channelsResource);
        when(channelsResource.list(anyList())).thenReturn(channelsListRequest);
        when(channelsListRequest.setId(anyList())).thenReturn(channelsListRequest);
    }

    private void stubSearchList(SearchListResponse response) throws IOException {
        when(youtube.search()).thenReturn(searchResource);
        when(searchResource.list(anyList())).thenReturn(searchListRequest);
        when(searchListRequest.setQ(org.mockito.ArgumentMatchers.anyString())).thenReturn(searchListRequest);
        when(searchListRequest.setType(anyList())).thenReturn(searchListRequest);
        when(searchListRequest.setMaxResults(org.mockito.ArgumentMatchers.anyLong())).thenReturn(searchListRequest);
        when(searchListRequest.execute()).thenReturn(response);
    }

    @Nested
    @DisplayName("fetchLiveStreamDetails()")
    class FetchLiveStreamDetails {

        @Test
        @DisplayName("正常系：高解像度サムネイルと開始時刻を含む配信詳細情報を取得できる")
        void testMethod01() throws IOException {
            VideoSnippet snippet = new VideoSnippet()
                    .setTitle("配信タイトル")
                    .setChannelId("UCxxxxxxxx")
                    .setChannelTitle("テストチャンネル")
                    .setDescription("説明文")
                    .setLiveBroadcastContent("live")
                    .setThumbnails(new ThumbnailDetails()
                            .setHigh(new Thumbnail().setUrl("https://example.com/high.jpg"))
                            .setDefault(new Thumbnail().setUrl("https://example.com/default.jpg")));

            VideoLiveStreamingDetails liveDetails = new VideoLiveStreamingDetails()
                    .setActualStartTime(new DateTime(1_700_000_000_000L));

            Video video = new Video().setSnippet(snippet).setLiveStreamingDetails(liveDetails);
            stubVideosList(new VideoListResponse().setItems(List.of(video)));

            Optional<LiveStreamDetails> result = youTubeApiClient.fetchLiveStreamDetails("video001");

            assertThat(result).isPresent();
            LiveStreamDetails details = result.get();
            assertThat(details.getVideoId()).isEqualTo("video001");
            assertThat(details.getTitle()).isEqualTo("配信タイトル");
            assertThat(details.getYoutubeChannelId()).isEqualTo("UCxxxxxxxx");
            assertThat(details.getChannelTitle()).isEqualTo("テストチャンネル");
            assertThat(details.getThumbnailUrl()).isEqualTo("https://example.com/high.jpg");
            assertThat(details.getActualStartTime()).isNotNull();
            assertThat(details.getScheduledStartTime()).isNull();
        }

        @Test
        @DisplayName("正常系：高解像度サムネイルが無い場合はdefaultサムネイルを使う")
        void testMethod02() throws IOException {
            VideoSnippet snippet = new VideoSnippet()
                    .setTitle("配信タイトル")
                    .setThumbnails(new ThumbnailDetails()
                            .setDefault(new Thumbnail().setUrl("https://example.com/default.jpg")));
            Video video = new Video().setSnippet(snippet);
            stubVideosList(new VideoListResponse().setItems(List.of(video)));

            Optional<LiveStreamDetails> result = youTubeApiClient.fetchLiveStreamDetails("video001");

            assertThat(result).isPresent();
            assertThat(result.get().getThumbnailUrl()).isEqualTo("https://example.com/default.jpg");
        }

        @Test
        @DisplayName("正常系：サムネイルが1つも無い場合はthumbnailUrlがnullになる")
        void testMethod03() throws IOException {
            VideoSnippet snippet = new VideoSnippet().setTitle("配信タイトル").setThumbnails(new ThumbnailDetails());
            Video video = new Video().setSnippet(snippet);
            stubVideosList(new VideoListResponse().setItems(List.of(video)));

            Optional<LiveStreamDetails> result = youTubeApiClient.fetchLiveStreamDetails("video001");

            assertThat(result).isPresent();
            assertThat(result.get().getThumbnailUrl()).isNull();
        }

        @Test
        @DisplayName("正常系：liveStreamingDetailsがnullの場合は開始時刻がnullのまま変換される")
        void testMethod04() throws IOException {
            VideoSnippet snippet = new VideoSnippet().setTitle("通常動画").setThumbnails(new ThumbnailDetails());
            Video video = new Video().setSnippet(snippet).setLiveStreamingDetails(null);
            stubVideosList(new VideoListResponse().setItems(List.of(video)));

            Optional<LiveStreamDetails> result = youTubeApiClient.fetchLiveStreamDetails("video001");

            assertThat(result).isPresent();
            assertThat(result.get().getActualStartTime()).isNull();
            assertThat(result.get().getScheduledStartTime()).isNull();
        }

        @Test
        @DisplayName("正常系：動画が見つからない場合（itemsが空）は空を返す")
        void testMethod05() throws IOException {
            stubVideosList(new VideoListResponse().setItems(List.of()));

            Optional<LiveStreamDetails> result = youTubeApiClient.fetchLiveStreamDetails("video001");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系：itemsがnullの場合も空を返す")
        void testMethod06() throws IOException {
            stubVideosList(new VideoListResponse());

            Optional<LiveStreamDetails> result = youTubeApiClient.fetchLiveStreamDetails("video001");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("異常系：IOExceptionが発生した場合は空を返す")
        void testMethod07() throws IOException {
            when(youtube.videos()).thenReturn(videosResource);
            when(videosResource.list(anyList())).thenReturn(videosListRequest);
            when(videosListRequest.setId(anyList())).thenReturn(videosListRequest);
            when(videosListRequest.execute()).thenThrow(new IOException("通信エラー"));

            Optional<LiveStreamDetails> result = youTubeApiClient.fetchLiveStreamDetails("video001");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("searchChannelsByName()")
    class SearchChannelsByName {

        @Test
        @DisplayName("正常系：検索結果をChannelSearchResultのリストに変換する")
        void testMethod01() throws IOException {
            SearchResult item = new SearchResult()
                    .setId(new ResourceId().setChannelId("UCxxxxxxxx"))
                    .setSnippet(new SearchResultSnippet()
                            .setTitle("テストチャンネル")
                            .setThumbnails(new ThumbnailDetails()
                                    .setDefault(new Thumbnail().setUrl("https://example.com/icon.jpg"))));
            stubSearchList(new SearchListResponse().setItems(List.of(item)));

            List<ChannelSearchResult> results = youTubeApiClient.searchChannelsByName("テスト");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).youtubeChannelId()).isEqualTo("UCxxxxxxxx");
            assertThat(results.get(0).channelTitle()).isEqualTo("テストチャンネル");
            assertThat(results.get(0).thumbnailUrl()).isEqualTo("https://example.com/icon.jpg");
        }

        @Test
        @DisplayName("正常系：itemsがnullの場合は空リストを返す")
        void testMethod02() throws IOException {
            stubSearchList(new SearchListResponse());

            List<ChannelSearchResult> results = youTubeApiClient.searchChannelsByName("テスト");

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("異常系：IOExceptionが発生した場合はYouTubeApiUnavailableExceptionを投げる")
        void testMethod03() throws IOException {
            when(youtube.search()).thenReturn(searchResource);
            when(searchResource.list(anyList())).thenReturn(searchListRequest);
            when(searchListRequest.setQ(org.mockito.ArgumentMatchers.anyString())).thenReturn(searchListRequest);
            when(searchListRequest.setType(anyList())).thenReturn(searchListRequest);
            when(searchListRequest.setMaxResults(org.mockito.ArgumentMatchers.anyLong())).thenReturn(searchListRequest);
            when(searchListRequest.execute()).thenThrow(new IOException("通信エラー"));

            assertThatThrownBy(() -> youTubeApiClient.searchChannelsByName("テスト"))
                    .isInstanceOf(YouTubeApiUnavailableException.class)
                    .hasMessage("YouTube API の呼び出しに失敗しました");
        }
    }

    @Nested
    @DisplayName("fetchChannelTitle()")
    class FetchChannelTitle {

        @Test
        @DisplayName("正常系：チャンネル名を前後の空白を除いて返す")
        void testMethod01() throws IOException {
            stubChannelsList();
            when(channelsListRequest.execute()).thenReturn(new ChannelListResponse().setItems(
                    List.of(new Channel().setSnippet(new ChannelSnippet().setTitle("  公式名  ")))));

            assertThat(youTubeApiClient.fetchChannelTitle("UCxxxxxxxx")).contains("公式名");
        }

        @Test
        @DisplayName("異常系：応答を解析できずIllegalArgumentExceptionが出ても、例外を投げずに空を返す")
        void testMethod02() throws IOException {
            stubChannelsList();
            when(channelsListRequest.execute()).thenThrow(new IllegalArgumentException("no JSON input found"));

            assertThat(youTubeApiClient.fetchChannelTitle("UCxxxxxxxx")).isEmpty();
        }

        @Test
        @DisplayName("異常系：途中で切れた応答でNullPointerExceptionが出ても、例外を投げずに空を返す")
        void testMethod03() throws IOException {
            stubChannelsList();
            when(channelsListRequest.execute()).thenThrow(new NullPointerException());

            assertThat(youTubeApiClient.fetchChannelTitle("UCxxxxxxxx")).isEmpty();
        }

        @Test
        @DisplayName("異常系：IOExceptionが発生した場合は空を返す")
        void testMethod04() throws IOException {
            stubChannelsList();
            when(channelsListRequest.execute()).thenThrow(new IOException("通信エラー"));

            assertThat(youTubeApiClient.fetchChannelTitle("UCxxxxxxxx")).isEmpty();
        }
    }
}
