package com.example.monitor.platform.youtube;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.platform.Platform;
import com.example.monitor.service.LiveStreamDetector;
import com.example.monitor.service.YouTubeApiClient;
import com.example.monitor.util.YouTubeWatchUrl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("YouTubeStreamPlatform")
class YouTubeStreamPlatformTest {

    @Mock
    private LiveStreamDetector liveStreamDetector;

    @Mock
    private YouTubeApiClient youTubeApiClient;

    @InjectMocks
    private YouTubeStreamPlatform platform;

    @Nested
    @DisplayName("platform()")
    class GetPlatform {

        @Test
        @DisplayName("正常系：YOUTUBEを返す")
        void testMethod01() {
            assertThat(platform.platform()).isEqualTo(Platform.YOUTUBE);
        }
    }

    @Nested
    @DisplayName("normalizeChannelInput()")
    class NormalizeChannelInput {

        @Test
        @DisplayName("正常系：チャンネルIDはそのまま返す")
        void testMethod01() {
            // チャンネルIDは UC で始まる24文字。この形に一致するものだけが ID として扱われる
            assertThat(platform.normalizeChannelInput("UCdyqAaZDKHXg4Ahi7VENThQ"))
                    .isEqualTo("UCdyqAaZDKHXg4Ahi7VENThQ");

            // ID が分かっているならクォータを使う必要はない
            verifyNoInteractions(youTubeApiClient);
        }

        @Test
        @DisplayName("正常系：@を付け忘れた入力もハンドルとして解決する")
        void testMethod07() {
            // @ が無いだけでチャンネルIDとみなすと 404 になり監視が永久に機能しなくなる
            // （mikenekoko / ShiroganeNoel の2件で実際に発生した）
            when(youTubeApiClient.resolveHandleToChannelId("@ShiroganeNoel"))
                    .thenReturn(Optional.of("UCdyqAaZDKHXg4Ahi7VENThQ"));

            assertThat(platform.normalizeChannelInput("ShiroganeNoel"))
                    .isEqualTo("UCdyqAaZDKHXg4Ahi7VENThQ");
        }

        @Test
        @DisplayName("正常系：チャンネルID形式のURLからIDを取り出す")
        void testMethod02() {
            assertThat(platform.normalizeChannelInput("https://www.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow"))
                    .isEqualTo("UCSJ4gkVC6NrvII8umztf0Ow");
        }

        @Test
        @DisplayName("正常系：ハンドル形式で指定すると本来のチャンネルIDに解決する")
        void testMethod03() {
            // ハンドルのまま保存すると /channel/{id}/live が404になり監視が静かに効かなくなる
            when(youTubeApiClient.resolveHandleToChannelId("@example")).thenReturn(Optional.of("UCresolved00"));

            assertThat(platform.normalizeChannelInput("@example")).isEqualTo("UCresolved00");
        }

        @Test
        @DisplayName("正常系：ハンドル形式のURLを貼り付けてもチャンネルIDへ解決する")
        void testMethod04() {
            when(youTubeApiClient.resolveHandleToChannelId("@seldea"))
                    .thenReturn(Optional.of("UC7Bb4I4tUzj1ftJI918QJsA"));

            assertThat(platform.normalizeChannelInput("https://www.youtube.com/@seldea"))
                    .isEqualTo("UC7Bb4I4tUzj1ftJI918QJsA");
        }

        @Test
        @DisplayName("異常系：ハンドルに該当するチャンネルが見つからない場合は例外を投げる")
        void testMethod05() {
            when(youTubeApiClient.resolveHandleToChannelId("@unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> platform.normalizeChannelInput("@unknown"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("@unknown");
        }

        @Test
        @DisplayName("異常系：空の入力は例外を投げる")
        void testMethod06() {
            assertThatThrownBy(() -> platform.normalizeChannelInput("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("supportsUrl()")
    class SupportsUrl {

        @Test
        @DisplayName("正常系：通常の視聴URLを担当する")
        void testMethod01() {
            assertThat(platform.supportsUrl("https://www.youtube.com/watch?v=aqz-KE-bpKQ")).isTrue();
        }

        @Test
        @DisplayName("正常系：短縮URL（youtu.be）も担当する")
        void testMethod02() {
            // 共有ボタンが作る形。yt-dlp が同じ動画IDに解決できることを確認済み
            assertThat(platform.supportsUrl("https://youtu.be/aqz-KE-bpKQ")).isTrue();
        }

        @Test
        @DisplayName("正常系：ShortsのURLも担当する")
        void testMethod03() {
            assertThat(platform.supportsUrl("https://www.youtube.com/shorts/abc12345678")).isTrue();
        }

        @Test
        @DisplayName("異常系：他プラットフォームのURLは担当しない")
        void testMethod04() {
            assertThat(platform.supportsUrl("https://www.twitch.tv/videos/2879280908")).isFalse();
        }

        @Test
        @DisplayName("異常系：動画URLでない文字列は担当しない")
        void testMethod05() {
            assertThat(platform.supportsUrl("これはURLではありません")).isFalse();
        }
    }

    @Nested
    @DisplayName("resolveChannelId()")
    class ResolveChannelId {

        @Test
        @DisplayName("正常系：channel_id をそのままチャンネルIDとして使う")
        void testMethod01() {
            VideoSource source = new VideoSource("youtube", "aqz-KE-bpKQ", "UCSMOQeBJ2RAnuFungnQOxLg",
                    "@BlenderOfficial", "was_live", "Big Buck Bunny");

            assertThat(platform.resolveChannelId(source)).contains("UCSMOQeBJ2RAnuFungnQOxLg");

            // ID がメタデータに入っているのでクォータを使う必要はない
            verifyNoInteractions(youTubeApiClient);
        }

        @Test
        @DisplayName("異常系：channel_id を取得できなかった場合は空を返す（ハンドルで代用しない）")
        void testMethod02() {
            // ハンドルは本来のチャンネルIDとは別物なので、代わりに使ってはいけない
            VideoSource source =
                    new VideoSource("youtube", "aqz-KE-bpKQ", null, "@BlenderOfficial", "was_live", "タイトル");

            assertThat(platform.resolveChannelId(source)).isEmpty();
        }
    }

    @Nested
    @DisplayName("isAvailable()")
    class IsAvailable {

        @Test
        @DisplayName("正常系：常に使える（配信中の判定にAPIキーが要らないため）")
        void testMethod01() {
            assertThat(platform.isAvailable()).isTrue();
        }
    }

    @Nested
    @DisplayName("detectLiveStream()")
    class DetectLiveStream {

        @Test
        @DisplayName("正常系：検知結果をそのまま返す")
        void testMethod01() {
            LiveStreamDetection detected = LiveStreamDetection.live(
                    "video001", "配信タイトル", null, "https://www.youtube.com/watch?v=video001");
            when(liveStreamDetector.detectLiveStream("UCxxxxxxxx")).thenReturn(detected);

            LiveStreamDetection result = platform.detectLiveStream("UCxxxxxxxx");

            assertThat(result.isLive()).isTrue();
            assertThat(result.watchUrl()).isEqualTo("https://www.youtube.com/watch?v=video001");
        }

        @Test
        @DisplayName("異常系：想定外の例外が出た場合は「判定できなかった」を返す")
        void testMethod02() {
            // notLive にすると検知の故障が平常運転に見えてしまう
            when(liveStreamDetector.detectLiveStream("UCxxxxxxxx"))
                    .thenThrow(new RuntimeException("想定外"));

            assertThat(platform.detectLiveStream("UCxxxxxxxx").isDetectionFailed()).isTrue();
        }
    }

    @Nested
    @DisplayName("detectLiveStreams()")
    class DetectLiveStreams {

        @Test
        @DisplayName("正常系：まとめて問い合わせられないため1件ずつ判定し、引数の順序を保って返す")
        void testMethod01() {
            when(liveStreamDetector.detectLiveStream("UCaaaaaaaa")).thenReturn(LiveStreamDetection.notLive());
            when(liveStreamDetector.detectLiveStream("UCbbbbbbbb")).thenReturn(LiveStreamDetection.live(
                    "video001", "配信タイトル", null, "https://www.youtube.com/watch?v=video001"));

            Map<String, LiveStreamDetection> results =
                    platform.detectLiveStreams(List.of("UCaaaaaaaa", "UCbbbbbbbb"));

            assertThat(results.keySet()).containsExactly("UCaaaaaaaa", "UCbbbbbbbb");
            assertThat(results.get("UCbbbbbbbb").isLive()).isTrue();
        }

        @Test
        @DisplayName("正常系：判定中はMDCにチャンネルIDが載る（チャンネル別ログへの振り分けが効く）")
        void testMethod02() {
            // この印が無いと、検知時のログが logs/channels/{チャンネルID}.log に残らなくなる
            List<String> observed = java.util.Collections.synchronizedList(new ArrayList<>());
            when(liveStreamDetector.detectLiveStream(anyString())).thenAnswer(invocation -> {
                observed.add(MDC.get("channelId"));
                return LiveStreamDetection.notLive();
            });

            platform.detectLiveStreams(List.of("UCaaaaaaaa", "UCbbbbbbbb"));

            assertThat(observed).containsExactlyInAnyOrder("UCaaaaaaaa", "UCbbbbbbbb");
            // 判定が終わったあとは印を残さない
            assertThat(MDC.get("channelId")).isNull();
        }
    }

    @Nested
    @DisplayName("fetchDetails()")
    class FetchDetails {

        @Test
        @DisplayName("正常系：視聴URLの入った詳細を返す")
        void testMethod01() {
            LiveStreamDetails details = LiveStreamDetails.builder()
                    .videoId("video001")
                    .watchUrl("https://www.youtube.com/watch?v=video001")
                    .build();
            when(youTubeApiClient.fetchLiveStreamDetails("video001")).thenReturn(Optional.of(details));

            assertThat(platform.fetchDetails("UCchannel001", "video001").orElseThrow().getWatchUrl())
                    .isEqualTo("https://www.youtube.com/watch?v=video001");
        }

        @Test
        @DisplayName("正常系：取得できなかった場合は空を返す")
        void testMethod02() {
            when(youTubeApiClient.fetchLiveStreamDetails("video001")).thenReturn(Optional.empty());

            assertThat(platform.fetchDetails("UCchannel001", "video001")).isEmpty();
        }
    }

    @Nested
    @DisplayName("fallbackDetails()")
    class FallbackDetails {

        @Test
        @DisplayName("正常系：検知結果とチャンネル名から通知に要る値を詰め、APIは呼ばない")
        void testMethod01() {
            // 動画 ID から組み立て直した URL（YouTubeWatchUrl.of）と見分けられるよう、それとは別の形にする
            String detectedWatchUrl = "https://www.youtube.com/live/abcdefghijk";
            LiveStreamDetection detection = LiveStreamDetection.live(
                    "abcdefghijk", "【雑談】おはよう", null, detectedWatchUrl);

            LiveStreamDetails details = platform.fallbackDetails("テストチャンネル", detection).orElseThrow();

            assertThat(details.getVideoId()).isEqualTo("abcdefghijk");
            assertThat(details.getTitle()).isEqualTo("【雑談】おはよう");
            assertThat(details.getChannelTitle()).isEqualTo("テストチャンネル");
            // 視聴 URL は検知結果が運んだものをそのまま使う
            assertThat(details.getWatchUrl()).isEqualTo(detectedWatchUrl);
            assertThat(details.getThumbnailUrl()).isEqualTo(YouTubeWatchUrl.thumbnailOf("abcdefghijk"));
            // API でしか分からない値は入れない（待機所の見送りの判定に使われないように）
            assertThat(details.getBroadcastStatus()).isNull();
            // クォータを使わない
            verifyNoInteractions(youTubeApiClient, liveStreamDetector);
        }

        @Test
        @DisplayName("正常系：タイトルが読めていなくても、空でない題を入れる")
        void testMethod02() {
            LiveStreamDetection detection = LiveStreamDetection.live(
                    "abcdefghijk", null, null, "https://www.youtube.com/watch?v=abcdefghijk");

            LiveStreamDetails details = platform.fallbackDetails("テストチャンネル", detection).orElseThrow();

            // Discord の埋め込みの題を空にしない
            assertThat(details.getTitle()).isNotBlank();
        }
    }
}
