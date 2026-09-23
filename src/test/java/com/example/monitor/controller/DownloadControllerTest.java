package com.example.monitor.controller;

import com.example.monitor.dto.DownloadRequest;
import com.example.monitor.dto.DownloadResponse;
import com.example.monitor.exception.VideoAlreadyDownloadedException;
import com.example.monitor.platform.Platform;
import com.example.monitor.service.VideoDownloadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadController")
class DownloadControllerTest {

    private static final String URL = "https://www.youtube.com/watch?v=aqz-KE-bpKQ";

    @Mock
    private VideoDownloadService videoDownloadService;

    @InjectMocks
    private DownloadController controller;

    @Nested
    @DisplayName("startDownload()")
    class StartDownload {

        @Test
        @DisplayName("正常系：受け付けた内容を202で返す")
        void testMethod01() {
            // ダウンロードの完了ではなく「受け付けた」ことを表す
            DownloadResponse accepted = new DownloadResponse(
                    10L, Platform.YOUTUBE, "aqz-KE-bpKQ", "Big Buck Bunny",
                    "UCSMOQeBJ2RAnuFungnQOxLg", "Blender", "UCSMOQeBJ2RAnuFungnQOxLg/aqz-KE-bpKQ.mp4");
            when(videoDownloadService.startDownload(URL)).thenReturn(accepted);

            ResponseEntity<DownloadResponse> response = controller.startDownload(new DownloadRequest(URL));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isEqualTo(accepted);
        }

        @Test
        @DisplayName("正常系：リクエストのURLをそのままサービスへ渡す")
        void testMethod02() {
            when(videoDownloadService.startDownload(URL)).thenReturn(new DownloadResponse(
                    10L, Platform.YOUTUBE, "aqz-KE-bpKQ", "タイトル", null, null, "downloads/aqz-KE-bpKQ.mp4"));

            controller.startDownload(new DownloadRequest(URL));

            verify(videoDownloadService).startDownload(URL);
        }

        @Test
        @DisplayName("異常系：既にダウンロード済みの場合の例外はそのまま伝える（409に変換される）")
        void testMethod03() {
            when(videoDownloadService.startDownload(URL))
                    .thenThrow(new VideoAlreadyDownloadedException("aqz-KE-bpKQ"));

            assertThatThrownBy(() -> controller.startDownload(new DownloadRequest(URL)))
                    .isInstanceOf(VideoAlreadyDownloadedException.class);
        }
    }
}
