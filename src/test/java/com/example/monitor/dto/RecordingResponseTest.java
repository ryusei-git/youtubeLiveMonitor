package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RecordingResponse")
class RecordingResponseTest {

    @Nested
    @DisplayName("from()")
    class From {

        @Test
        @DisplayName("正常系：完了した録画の全項目がレスポンスに詰め替えられる")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setId(1L);
            LocalDateTime startedAt = LocalDateTime.of(2026, 9, 13, 10, 0, 0);
            LocalDateTime completedAt = LocalDateTime.of(2026, 9, 13, 12, 0, 0);

            Recording recording = Recording.builder()
                    .id(10L)
                    .channel(channel)
                    .videoId("video001")
                    .videoTitle("配信タイトル")
                    .filePath("UCxxxxxxxx/video001.mp4")
                    .fileSizeBytes(12345L)
                    .status(RecordingStatus.COMPLETED)
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .build();

            RecordingResponse response = RecordingResponse.from(recording);

            assertThat(response.id()).isEqualTo(10L);
            assertThat(response.youtubeChannelId()).isEqualTo("UCxxxxxxxx");
            assertThat(response.channelName()).isEqualTo("テストチャンネル");
            assertThat(response.videoId()).isEqualTo("video001");
            assertThat(response.videoTitle()).isEqualTo("配信タイトル");
            assertThat(response.filePath()).isEqualTo("UCxxxxxxxx/video001.mp4");
            assertThat(response.fileSizeBytes()).isEqualTo(12345L);
            assertThat(response.status()).isEqualTo("COMPLETED");
            assertThat(response.startedAt()).isEqualTo(startedAt);
            assertThat(response.completedAt()).isEqualTo(completedAt);
        }

        @Test
        @DisplayName("正常系：録画中の録画はファイルサイズ・完了時刻がnullとして詰め替えられる")
        void testMethod02() {
            MonitoredChannel channel = new MonitoredChannel("UCyyyyyyyy", "別チャンネル");
            channel.setId(2L);

            Recording recording = Recording.builder()
                    .id(11L)
                    .channel(channel)
                    .videoId("video002")
                    .videoTitle("配信タイトル2")
                    .filePath("UCyyyyyyyy/video002.mp4")
                    .status(RecordingStatus.RECORDING)
                    .startedAt(LocalDateTime.now())
                    .build();

            RecordingResponse response = RecordingResponse.from(recording);

            assertThat(response.status()).isEqualTo("RECORDING");
            assertThat(response.fileSizeBytes()).isNull();
            assertThat(response.completedAt()).isNull();
        }

        @Test
        @DisplayName("正常系：チャンネルに紐づかない録画でも落ちず、未登録と分かる形で返す")
        void testMethod03() {
            // URL 指定でダウンロードした、監視登録していないチャンネルの動画がこれにあたる。
            // 一覧・再生画面の両方がこのメソッドを通るため、ここで吸収しないと画面が落ちる
            Recording recording = Recording.builder()
                    .id(12L)
                    .channel(null)
                    .videoId("aqz-KE-bpKQ")
                    .videoTitle("Big Buck Bunny")
                    .filePath("downloads/aqz-KE-bpKQ.mp4")
                    .fileSizeBytes(2048L)
                    .status(RecordingStatus.COMPLETED)
                    .startedAt(LocalDateTime.now())
                    .build();

            RecordingResponse response = RecordingResponse.from(recording);

            assertThat(response.channelId()).isNull();
            assertThat(response.youtubeChannelId()).isNull();
            assertThat(response.channelName()).isEqualTo("(未登録チャンネル)");
            assertThat(response.videoTitle()).isEqualTo("Big Buck Bunny");
            assertThat(response.filePath()).isEqualTo("downloads/aqz-KE-bpKQ.mp4");
        }
    }
}
