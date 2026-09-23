package com.example.monitor.service;
import com.example.monitor.entity.OnlineVideo;
import com.example.monitor.entity.VideoThumbnail;
import com.example.monitor.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.net.http.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class VideoThumbnailServiceTest {
    @Mock OnlineVideoRepository videos;
    @Mock VideoThumbnailRepository thumbnails;
    @Mock HttpClient http;
    @InjectMocks VideoThumbnailService service;
    @Nested class CaptureMissing {
        @Test @DisplayName("正常系：サムネイル画像本体を保存する")
        void testMethod01() throws Exception {
            var video = new OnlineVideo(); video.setId("YOUTUBE_abcdefghijk");
            video.setThumbnailUrl("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg");
            when(videos.withoutThumbnail(any())).thenReturn(List.of(video));
            HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("image/jpeg")), (a,b)->true));
            when(response.body()).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
            when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            service.captureMissing();
            var capture = ArgumentCaptor.forClass(VideoThumbnail.class); verify(thumbnails).save(capture.capture());
            assertThat(capture.getValue().getContent()).containsExactly(1,2,3);
        }
        @Test @DisplayName("異常系：許可していないホストから画像をダウンロードしない")
        void testMethod02() {
            var video = new OnlineVideo(); video.setThumbnailUrl("http://127.0.0.1/private");
            when(videos.withoutThumbnail(any())).thenReturn(List.of(video));
            service.captureMissing(); verifyNoInteractions(http, thumbnails);
        }
    }
}
