package com.example.monitor.service;

import com.example.monitor.dto.LiveStreamDetection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LiveStreamDetector")
@SuppressWarnings("unchecked")
class LiveStreamDetectorTest {

    @Mock
    private HttpClient httpClient;

    @InjectMocks
    private LiveStreamDetector liveStreamDetector;

    private void stubResponse(int statusCode, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        // ステータスコードが200以外のテストでは body() が呼ばれないため lenient にする
        lenient().when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    @Nested
    @DisplayName("detectLiveStream()")
    class DetectLiveStream {

        @Test
        @DisplayName("正常系：配信中の場合はcanonicalタグから動画IDとタイトルを抽出する")
        void testMethod01() throws IOException, InterruptedException {
            String html = """
                    <html><head>
                    <link rel="canonical" href="https://www.youtube.com/watch?v=abcdefg1234">
                    <meta name="title" content="【ASMR】耳かき音フェチ">
                    </head></html>
                    """;
            stubResponse(200, html);

            LiveStreamDetection result = liveStreamDetector.detectLiveStream("UCxxxxxxxx");

            assertThat(result.isLive()).isTrue();
            assertThat(result.videoId()).isEqualTo("abcdefg1234");
            assertThat(result.title()).isEqualTo("【ASMR】耳かき音フェチ");
            // 待機所ではないのにUPCOMINGへ倒れると、実際に配信中の枠が通知・録画の対象から漏れる
            assertThat(result.isUpcoming()).isFalse();
        }

        @Test
        @DisplayName("正常系：タイトルのmetaタグが無い場合はtitleがnullになる")
        void testMethod02() throws IOException, InterruptedException {
            String html = """
                    <html><head>
                    <link rel="canonical" href="https://www.youtube.com/watch?v=abcdefg1234">
                    </head></html>
                    """;
            stubResponse(200, html);

            LiveStreamDetection result = liveStreamDetector.detectLiveStream("UCxxxxxxxx");

            assertThat(result.isLive()).isTrue();
            assertThat(result.title()).isNull();
        }

        @Test
        @DisplayName("正常系：配信開始前の待機所（isUpcoming:trueを含む）はUPCOMINGと判定する")
        void testMethod03() throws IOException, InterruptedException {
            // 実際にYouTubeの待機所ページで観測されたJSON断片を模したもの。
            // canonicalは配信中と同じくwatch?v=を指すため、この追加チェックが無いと誤検知する
            // （実際に発生した：配信開始の146日も前から「配信中」と誤判定され、Discordに誤通知が飛んだ）。
            String html = """
                    <html><head>
                    <link rel="canonical" href="https://www.youtube.com/watch?v=abcdefg1234">
                    <meta name="title" content="【CHAT Room】待機所">
                    </head><body>
                    <script>var ytInitialData = {"isUpcoming":true,"scheduledStartTime":"1790251200","allowRatings":true};</script>
                    </body></html>
                    """;
            stubResponse(200, html);

            LiveStreamDetection result = liveStreamDetector.detectLiveStream("UCxxxxxxxx");

            assertThat(result.status()).isEqualTo(LiveStreamDetection.DetectionStatus.UPCOMING);
            assertThat(result.isUpcoming()).isTrue();
            assertThat(result.title()).isEqualTo("【CHAT Room】待機所");
            assertThat(result.watchUrl()).isEqualTo("https://www.youtube.com/watch?v=abcdefg1234");
            assertThat(result.scheduledStartTime()).isEqualTo(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(1790251200L), ZoneId.systemDefault()));
            assertThat(result.isDetectionFailed()).isFalse();
            // 配信開始の146日も前から「配信中」と誤判定しDiscordに誤通知が飛んだ事故の再発防止
            assertThat(result.isLive()).isFalse();
        }

        @Test
        @DisplayName("正常系：待機所のHTMLに開始予定時刻が無い場合はscheduledStartTimeをnullにするが判定は壊さない")
        void testMethod08() throws IOException, InterruptedException {
            // isUpcoming はあるが scheduledStartTime を欠いた断片（HTML構造の変化を模す）
            String html = """
                    <html><head>
                    <link rel="canonical" href="https://www.youtube.com/watch?v=abcdefg1234">
                    <meta name="title" content="【CHAT Room】待機所">
                    </head><body>
                    <script>var ytInitialData = {"isUpcoming":true,"allowRatings":true};</script>
                    </body></html>
                    """;
            stubResponse(200, html);

            LiveStreamDetection result = liveStreamDetector.detectLiveStream("UCxxxxxxxx");

            // 時刻が取れなくても、待機所であること自体は分かっているので判定失敗にはしない
            assertThat(result.status()).isEqualTo(LiveStreamDetection.DetectionStatus.UPCOMING);
            assertThat(result.scheduledStartTime()).isNull();
            assertThat(result.isDetectionFailed()).isFalse();
        }

        @Test
        @DisplayName("正常系：canonicalがチャンネルの/liveページを指す場合は「配信していない」と判定する")
        void testMethod04() throws IOException, InterruptedException {
            String html = """
                    <html><head>
                    <link rel="canonical" href="https://www.youtube.com/channel/UCxxxxxxxx/live">
                    </head></html>
                    """;
            stubResponse(200, html);

            LiveStreamDetection result = liveStreamDetector.detectLiveStream("UCxxxxxxxx");

            // 正常に判定できた結果としての「配信していない」なので、判定失敗と混同してはいけない
            assertThat(result.status()).isEqualTo(LiveStreamDetection.DetectionStatus.NOT_LIVE);
            assertThat(result.isDetectionFailed()).isFalse();
        }

        @Test
        @DisplayName("異常系：ステータスコードが200以外の場合は「判定できなかった」として扱う")
        void testMethod05() throws IOException, InterruptedException {
            stubResponse(404, "");

            LiveStreamDetection result = liveStreamDetector.detectLiveStream("UCxxxxxxxx");

            assertThat(result.status()).isEqualTo(LiveStreamDetection.DetectionStatus.DETECTION_FAILED);
            assertThat(result.isLive()).isFalse();
        }

        @Test
        @DisplayName("異常系：canonicalタグが存在しない場合は「判定できなかった」として扱う")
        void testMethod06() throws IOException, InterruptedException {
            stubResponse(200, "<html><head></head><body>no canonical here</body></html>");

            LiveStreamDetection result = liveStreamDetector.detectLiveStream("UCxxxxxxxx");

            assertThat(result.status()).isEqualTo(LiveStreamDetection.DetectionStatus.DETECTION_FAILED);
            assertThat(result.isLive()).isFalse();
        }

        @Test
        @DisplayName("異常系：通信中に例外が発生した場合は「判定できなかった」として扱う")
        void testMethod07() throws IOException, InterruptedException {
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("接続に失敗しました"));

            LiveStreamDetection result = liveStreamDetector.detectLiveStream("UCxxxxxxxx");

            assertThat(result.status()).isEqualTo(LiveStreamDetection.DetectionStatus.DETECTION_FAILED);
            assertThat(result.isLive()).isFalse();
        }
    }
}
