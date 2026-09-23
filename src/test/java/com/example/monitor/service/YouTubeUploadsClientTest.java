package com.example.monitor.service;

import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import com.google.api.client.util.DateTime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class YouTubeUploadsClientTest {
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) YouTube youtube;
    @Mock YouTubeCatalogQuota quota;
    @InjectMocks YouTubeUploadsClient client;
    @Nested class Fetch {
        @Test @DisplayName("正常系：投稿一覧IDを公式応答から取得し再利用してURLを収集する")
        void testMethod01() throws Exception {
            String id = "UCabcdefghijklmnopqrstuv";
            var channels = youtube.channels().list(List.of("contentDetails")).setId(List.of(id));
            when(channels.execute()).thenReturn(new ChannelListResponse().setItems(List.of(
                new Channel().setId(id).setContentDetails(new ChannelContentDetails()
                    .setRelatedPlaylists(new ChannelContentDetails.RelatedPlaylists().setUploads("uploads-id"))))));
            var items = youtube.playlistItems().list(List.of("snippet", "contentDetails"))
                    .setPlaylistId("uploads-id").setMaxResults(50L).setPageToken(null);
            when(items.execute()).thenReturn(new PlaylistItemListResponse().setItems(List.of(new PlaylistItem()
                    .setSnippet(new PlaylistItemSnippet().setTitle("新着"))
                    .setContentDetails(new PlaylistItemContentDetails().setVideoId("abcdefghijk")
                        .setVideoPublishedAt(new DateTime("2026-09-23T01:00:00Z"))))));
            var videos = client.fetch(id, Instant.parse("2026-09-22T00:00:00Z"));
            client.fetch(id, Instant.parse("2026-09-22T00:00:00Z"));
            assertThat(videos.getFirst().watchUrl()).isEqualTo("https://www.youtube.com/watch?v=abcdefghijk");
            verify(channels, times(1)).execute(); verify(quota, times(3)).acquire();
        }
    }
}
