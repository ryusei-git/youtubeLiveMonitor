package com.example.monitor.scheduler;
import com.example.monitor.dto.OnlineVideoCandidate;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.twitch.TwitchApiClient;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnlineVideoCollectorTest {
    @Mock MonitoredChannelRepository channels;
    @Mock YouTubeVideoFeedClient youtube;
    @Mock YouTubeUploadsClient youtubeUploads;
    @Mock TwitchApiClient twitch;
    @Mock OnlineVideoService videos;
    @Mock VideoThumbnailService thumbnails;
    @Mock VideoCollectionTracker tracker;
    @InjectMocks OnlineVideoCollector collector;
    @Nested class Collect {
        @Test @DisplayName("異常系：一件の取得失敗を新着なしにせず他のチャンネルは収集を続ける")
        void testMethod01() throws Exception {
            var a = new MonitoredChannel("UCabcdefghijklmnopqrstuv", "A");
            var b = new MonitoredChannel("UC1234567890123456789012", "B");
            var candidate = new OnlineVideoCandidate("YOUTUBE_abcdefghijk", "新着", "https://www.youtube.com/watch?v=abcdefghijk", "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", Instant.now());
            when(channels.findAll()).thenReturn(List.of(a,b));
            when(youtube.fetch(a.getYoutubeChannelId())).thenThrow(new IOException("取得失敗"));
            when(youtubeUploads.fetch(eq(a.getYoutubeChannelId()), any())).thenThrow(new IOException("API取得失敗"));
            when(youtube.fetch(b.getYoutubeChannelId())).thenReturn(List.of(candidate));
            collector.collect();
            verify(tracker).checked(a,false); verify(tracker).checked(b,true);
            verify(videos).capture(b,candidate); verify(thumbnails).captureMissing();
        }
    }
}
