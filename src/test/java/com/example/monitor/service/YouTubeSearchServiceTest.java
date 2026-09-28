package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.YouTubeSearchRequest;
import com.example.monitor.dto.YouTubeSearchResponse;
import com.example.monitor.dto.YouTubeVideoResponse;
import com.example.monitor.exception.SearchQuotaExceededException;
import com.example.monitor.exception.YouTubeApiUnavailableException;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelSnippet;
import com.google.api.services.youtube.model.ChannelStatistics;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoContentDetails;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("YouTubeSearchService")
class YouTubeSearchServiceTest {

    private static final String CHANNEL_ID = "UCaaaaaaaaaaaaaaaaaaaaaa";

    @Mock
    private YouTubeSearchClient client;

    @Mock
    private YouTubeSearchBudget budget;

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private RecordingRepository recordingRepository;

    private YouTubeSearchService service;

    @BeforeEach
    void setUp() {
        service = new YouTubeSearchService(client, budget, monitoredChannelRepository, recordingRepository,
                properties("test-key"));
    }

    private static MonitorProperties properties(String apiKey) {
        return new MonitorProperties(new MonitorProperties.YouTubeProperties(apiKey, 120,
                new MonitorProperties.SearchProperties(52, 12, 10)), null, null, null, null);
    }

    /** 必要な条件だけを入れた YouTubeSearchRequest を作る（ほかは null）。 */
    private static final class Req {
        String q = "vtuber";
        String eventType;
        String safeSearch;
        String pageToken;
        Integer minDurationSec;
        Long minViews;
        Long minLikes;
        Long maxSubscribers;
        Boolean excludeShorts;
        String titleIncludes;
        String titleExcludes;

        YouTubeSearchRequest build() {
            return new YouTubeSearchRequest(q, null, null, null, null, eventType, null, null, null, null, null,
                    safeSearch, pageToken, minDurationSec, null, minViews, null, minLikes, maxSubscribers, null,
                    excludeShorts, titleIncludes, titleExcludes, null, null, null, null);
        }
    }

    private static Video video(String id, String channelId, String liveBroadcastContent, String isoDuration,
                               Long views, Long likes) {
        VideoStatistics statistics = new VideoStatistics();
        if (views != null) {
            statistics.setViewCount(BigInteger.valueOf(views));
        }
        if (likes != null) {
            statistics.setLikeCount(BigInteger.valueOf(likes));
        }
        return new Video()
                .setId(id)
                .setSnippet(new VideoSnippet().setChannelId(channelId).setLiveBroadcastContent(liveBroadcastContent))
                .setContentDetails(new VideoContentDetails().setDuration(isoDuration))
                .setStatistics(statistics);
    }

    private static Channel channel(String id, Long subscribers, boolean hidden) {
        ChannelStatistics statistics = new ChannelStatistics().setHiddenSubscriberCount(hidden);
        if (subscribers != null) {
            statistics.setSubscriberCount(BigInteger.valueOf(subscribers));
        }
        return new Channel()
                .setId(id)
                .setSnippet(new ChannelSnippet().setTitle("チャンネル " + id))
                .setStatistics(statistics);
    }

    private static YouTubeSearchClient.SearchPage page(String nextPageToken, String... videoIds) {
        return new YouTubeSearchClient.SearchPage(List.of(videoIds), nextPageToken);
    }

    private static GoogleJsonResponseException apiError(int status, String reason) {
        GoogleJsonError.ErrorInfo info = new GoogleJsonError.ErrorInfo();
        info.setReason(reason);
        GoogleJsonError details = new GoogleJsonError();
        details.setCode(status);
        details.setErrors(List.of(info));
        return new GoogleJsonResponseException(new HttpResponseException.Builder(status, null, new HttpHeaders()), details);
    }

    /** 渡された動画 ID ごとに、長さ 20 分・再生 500 回の動画を返すようにする。 */
    private void stubVideosFor500Views() throws IOException {
        when(client.fetchVideos(any())).thenAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(0);
            return ids.stream().map(id -> video(id, CHANNEL_ID, "none", "PT20M", 500L, null)).toList();
        });
    }

    private static List<String> videoIdsOf(YouTubeSearchResponse response) {
        return response.items().stream().map(YouTubeVideoResponse::videoId).toList();
    }

    @Nested
    @DisplayName("search()")
    class Search {

        @Test
        @DisplayName("正常系：公式の条件が同じなら、このサービスの条件だけを変えた 2 回目は API も回数も使わない")
        void testMethod01() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(page(null, "vid00000001"));
            stubVideosFor500Views();
            Req req = new Req();

            YouTubeSearchResponse unfiltered = service.search(req.build(), "alice");
            req.minViews = 100L;
            YouTubeSearchResponse filtered = service.search(req.build(), "alice");

            assertThat(videoIdsOf(unfiltered)).containsExactly("vid00000001");
            assertThat(videoIdsOf(filtered)).containsExactly("vid00000001");
            assertThat(filtered.filteredByService()).isTrue();
            verify(client, times(1)).searchVideoIds(any(), any());
            verify(budget, times(1)).acquireForUser("alice");
        }

        @Test
        @DisplayName("正常系：safeSearch の省略と moderate は同じ条件として使い回す")
        void testMethod02() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(page(null, "vid00000001"));
            stubVideosFor500Views();
            Req req = new Req();

            service.search(req.build(), "alice");
            req.safeSearch = "moderate";
            service.search(req.build(), "alice");

            verify(client, times(1)).searchVideoIds(any(), any());
            verify(budget, times(1)).acquireForUser("alice");
        }

        @Test
        @DisplayName("正常系：公式の条件や pageToken が違えば、そのたびに回数を使う")
        void testMethod03() throws Exception {
            when(client.searchVideoIds(any(), any())).thenReturn(page(null, "vid00000001"));
            stubVideosFor500Views();
            Req base = new Req();
            Req otherQuery = new Req();
            otherQuery.q = "asmr";
            Req otherPage = new Req();
            otherPage.pageToken = "P2";

            service.search(base.build(), "alice");
            service.search(otherQuery.build(), "alice");
            service.search(otherPage.build(), "alice");

            verify(budget, times(3)).acquireForUser("alice");
            verify(client, times(3)).searchVideoIds(any(), any());
        }

        @Test
        @DisplayName("異常系：API キーが空なら API も回数も使わずに失敗する")
        void testMethod04() {
            YouTubeSearchService withoutKey = new YouTubeSearchService(client, budget, monitoredChannelRepository,
                    recordingRepository, properties(""));
            YouTubeSearchRequest request = new Req().build();

            assertThatThrownBy(() -> withoutKey.search(request, "alice"))
                    .isInstanceOf(YouTubeApiUnavailableException.class)
                    .hasMessage("YouTube の API キーが設定されていません");
            verifyNoInteractions(budget, client);
        }

        @Test
        @DisplayName("異常系：検索語もチャンネル ID も無ければ回数を使わずに 400 にする")
        void testMethod05() {
            Req req = new Req();
            req.q = " ";
            YouTubeSearchRequest request = req.build();

            assertThatThrownBy(() -> service.search(request, "alice")).isInstanceOf(IllegalArgumentException.class);
            verify(budget, never()).acquireForUser(any());
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("異常系：403 の quotaExceeded なら今日の検索を止めて 429 にする")
        void testMethod06() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenThrow(apiError(403, "quotaExceeded"));
            YouTubeSearchRequest request = new Req().build();

            assertThatThrownBy(() -> service.search(request, "alice"))
                    .isInstanceOf(SearchQuotaExceededException.class);
            verify(budget, times(1)).markExhausted();
        }

        @Test
        @DisplayName("異常系：400 で理由が invalid で始まるなら、条件の誤りとして 400 にする")
        void testMethod07() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenThrow(apiError(400, "invalidSearchFilter"));
            YouTubeSearchRequest request = new Req().build();

            assertThatThrownBy(() -> service.search(request, "alice"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalidSearchFilter");
            verify(budget, never()).markExhausted();
        }

        @Test
        @DisplayName("異常系：キーの誤り（400 keyInvalid）は利用者に直せないので 503 にする")
        void testMethod08() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenThrow(apiError(400, "keyInvalid"));
            YouTubeSearchRequest request = new Req().build();

            assertThatThrownBy(() -> service.search(request, "alice"))
                    .isInstanceOf(YouTubeApiUnavailableException.class)
                    .hasMessage("YouTube API の呼び出しに失敗しました");
            verify(budget, never()).markExhausted();
        }

        @Test
        @DisplayName("異常系：接続の失敗も 503 にする")
        void testMethod09() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenThrow(new IOException("timeout"));
            YouTubeSearchRequest request = new Req().build();

            assertThatThrownBy(() -> service.search(request, "alice"))
                    .isInstanceOf(YouTubeApiUnavailableException.class)
                    .hasMessage("YouTube API の呼び出しに失敗しました");
        }

        @Test
        @DisplayName("正常系：このサービスの条件で 20 件を下回れば次のページを 1 回だけ読み、重複を除いて足す")
        void testMethod10() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(page("P2", "vid00000001", "vid00000002"));
            when(client.searchVideoIds(any(), eq("P2"))).thenReturn(page("P3", "vid00000002", "vid00000003"));
            stubVideosFor500Views();
            Req req = new Req();
            req.minViews = 100L;

            YouTubeSearchResponse response = service.search(req.build(), "alice");

            assertThat(videoIdsOf(response)).containsExactly("vid00000001", "vid00000002", "vid00000003");
            assertThat(response.nextPageToken()).isEqualTo("P3");
            verify(client, never()).searchVideoIds(any(), eq("P3"));
            verify(budget, times(2)).acquireForUser("alice");
        }

        @Test
        @DisplayName("正常系：このサービスの条件が無ければ次のページを読まない")
        void testMethod11() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(page("P2", "vid00000001"));
            stubVideosFor500Views();

            YouTubeSearchResponse response = service.search(new Req().build(), "alice");

            assertThat(videoIdsOf(response)).containsExactly("vid00000001");
            assertThat(response.nextPageToken()).isEqualTo("P2");
            verify(client, never()).searchVideoIds(any(), eq("P2"));
        }

        @Test
        @DisplayName("正常系：次のページで検索の上限に達しても、1 ページ目の結果と 1 ページ目の続きの印を返す")
        void testMethod12() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(page("P2", "vid00000001"));
            stubVideosFor500Views();
            doNothing().doThrow(new SearchQuotaExceededException()).when(budget).acquireForUser("alice");
            Req req = new Req();
            req.minViews = 100L;

            YouTubeSearchResponse response = service.search(req.build(), "alice");

            assertThat(videoIdsOf(response)).containsExactly("vid00000001");
            assertThat(response.nextPageToken()).isEqualTo("P2");
            verify(client, never()).searchVideoIds(any(), eq("P2"));
        }

        @Test
        @DisplayName("正常系：次のページが API の失敗でも、1 ページ目の結果と 1 ページ目の続きの印を返す")
        void testMethod13() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(page("P2", "vid00000001"));
            when(client.searchVideoIds(any(), eq("P2"))).thenThrow(new IOException("timeout"));
            stubVideosFor500Views();
            Req req = new Req();
            req.minViews = 100L;

            YouTubeSearchResponse response = service.search(req.build(), "alice");

            assertThat(videoIdsOf(response)).containsExactly("vid00000001");
            assertThat(response.nextPageToken()).isEqualTo("P2");
        }

        @Test
        @DisplayName("正常系：次のページを YouTube が受け付けなければ、1 ページ目の結果だけを返し続きの印は返さない")
        void testMethod14() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(page("P2", "vid00000001"));
            when(client.searchVideoIds(any(), eq("P2"))).thenThrow(apiError(400, "invalidPageToken"));
            stubVideosFor500Views();
            Req req = new Req();
            req.minViews = 100L;

            YouTubeSearchResponse response = service.search(req.build(), "alice");

            assertThat(videoIdsOf(response)).containsExactly("vid00000001");
            assertThat(response.nextPageToken()).isNull();
        }

        @Test
        @DisplayName("正常系：詳細の取得だけが失敗したら、やり直しで検索の回数を使わない")
        void testMethod15() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(page(null, "vid00000001"));
            Video found = video("vid00000001", CHANNEL_ID, "none", "PT20M", 500L, null);
            when(client.fetchVideos(any())).thenThrow(new IOException("timeout")).thenReturn(List.of(found));
            YouTubeSearchRequest request = new Req().build();

            assertThatThrownBy(() -> service.search(request, "alice"))
                    .isInstanceOf(YouTubeApiUnavailableException.class);
            YouTubeSearchResponse retried = service.search(request, "alice");

            assertThat(videoIdsOf(retried)).containsExactly("vid00000001");
            verify(client, times(1)).searchVideoIds(any(), any());
            verify(budget, times(1)).acquireForUser("alice");
            verify(client, times(2)).fetchVideos(any());
        }

        @Test
        @DisplayName("正常系：同じ条件の検索が重なったら、後の方は先の読み込みを待ち、回数を使わない")
        void testMethod16() throws Exception {
            YouTubeSearchClient.SearchPage page = page(null, "vid00000001");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(client.searchVideoIds(any(), isNull())).thenAnswer(invocation -> {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();   // 先の要求はここで止まる（TIMED_WAITING）
                return page;
            });
            stubVideosFor500Views();
            YouTubeSearchRequest request = new Req().build();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<YouTubeSearchResponse> first = pool.submit(() -> service.search(request, "alice"));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                AtomicReference<Object> secondResult = new AtomicReference<>();   // 戻り値か例外を入れる
                Thread second = new Thread(() -> {
                    try {
                        secondResult.set(service.search(request, "bob"));
                    } catch (RuntimeException e) {
                        secondResult.set(e);
                    }
                });
                second.start();
                awaitWaiting(second);
                assertThat(second.getState()).isEqualTo(Thread.State.WAITING);   // 先の読み込みの終わりを待っている
                release.countDown();
                YouTubeSearchResponse firstResponse = first.get(5, TimeUnit.SECONDS);
                second.join(5000);

                assertThat(second.isAlive()).isFalse();
                assertThat(videoIdsOf(firstResponse)).containsExactly("vid00000001");
                assertThat(secondResult.get()).isInstanceOf(YouTubeSearchResponse.class);
                assertThat(videoIdsOf((YouTubeSearchResponse) secondResult.get())).containsExactly("vid00000001");
                verify(client, times(1)).searchVideoIds(any(), any());
                verify(budget, times(1)).acquireForUser("alice");
                verify(budget, never()).acquireForUser("bob");
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("異常系：先の読み込みが失敗したら、待っていた方は自分の回数で読み直す")
        void testMethod17() throws Exception {
            YouTubeSearchClient.SearchPage page = page(null, "vid00000001");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(client.searchVideoIds(any(), isNull())).thenAnswer(invocation -> {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();   // 先の要求はここで止まる（TIMED_WAITING）
                throw new IOException("timeout");
            }).thenReturn(page);
            stubVideosFor500Views();
            YouTubeSearchRequest request = new Req().build();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<YouTubeSearchResponse> first = pool.submit(() -> service.search(request, "alice"));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                AtomicReference<Object> secondResult = new AtomicReference<>();   // 戻り値か例外を入れる
                Thread second = new Thread(() -> {
                    try {
                        secondResult.set(service.search(request, "bob"));
                    } catch (RuntimeException e) {
                        secondResult.set(e);
                    }
                });
                second.start();
                awaitWaiting(second);
                assertThat(second.getState()).isEqualTo(Thread.State.WAITING);   // 先の読み込みの終わりを待っている
                release.countDown();
                assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(YouTubeApiUnavailableException.class);
                second.join(5000);

                assertThat(second.isAlive()).isFalse();
                assertThat(secondResult.get()).isInstanceOf(YouTubeSearchResponse.class);
                assertThat(videoIdsOf((YouTubeSearchResponse) secondResult.get())).containsExactly("vid00000001");
                verify(client, times(2)).searchVideoIds(any(), any());
                verify(budget, times(1)).acquireForUser("alice");
                verify(budget, times(1)).acquireForUser("bob");
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("正常系：長さの条件は配信中・予約枠には当てない")
        void testMethod18() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(
                    page(null, "vid00000001", "vid00000002", "vid00000003", "vid00000004"));
            List<Video> videos = List.of(
                    video("vid00000001", CHANNEL_ID, "live", "P0D", 500L, null),
                    video("vid00000002", CHANNEL_ID, "upcoming", "P0D", 500L, null),
                    video("vid00000003", CHANNEL_ID, "none", "PT5M", 500L, null),
                    video("vid00000004", CHANNEL_ID, "none", "PT20M", 500L, null));
            when(client.fetchVideos(any())).thenReturn(videos);
            Req req = new Req();
            req.minDurationSec = 600;
            req.excludeShorts = true;

            YouTubeSearchResponse response = service.search(req.build(), "alice");

            assertThat(videoIdsOf(response)).containsExactly("vid00000001", "vid00000002", "vid00000004");
        }

        @Test
        @DisplayName("正常系：ショートを除く条件は 180 秒ちょうどを落とし、181 秒を残す")
        void testMethod19() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(page(null, "vid00000001", "vid00000002"));
            List<Video> videos = List.of(
                    video("vid00000001", CHANNEL_ID, "none", "PT3M", 500L, null),
                    video("vid00000002", CHANNEL_ID, "none", "PT3M1S", 500L, null));
            when(client.fetchVideos(any())).thenReturn(videos);
            Req req = new Req();
            req.excludeShorts = true;

            YouTubeSearchResponse response = service.search(req.build(), "alice");

            assertThat(videoIdsOf(response)).containsExactly("vid00000002");
        }

        @Test
        @DisplayName("正常系：高評価の下限は、高評価が非公開の動画を落とす")
        void testMethod20() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(
                    page(null, "vid00000001", "vid00000002", "vid00000003"));
            List<Video> videos = List.of(
                    video("vid00000001", CHANNEL_ID, "none", "PT20M", 500L, null),
                    video("vid00000002", CHANNEL_ID, "none", "PT20M", 500L, 9L),
                    video("vid00000003", CHANNEL_ID, "none", "PT20M", 500L, 10L));
            when(client.fetchVideos(any())).thenReturn(videos);
            Req req = new Req();
            req.minLikes = 10L;

            YouTubeSearchResponse response = service.search(req.build(), "alice");

            assertThat(videoIdsOf(response)).containsExactly("vid00000003");
        }

        @Test
        @DisplayName("正常系：登録者の上限は、登録者数が非公開のチャンネルを残す")
        void testMethod21() throws Exception {
            when(client.searchVideoIds(any(), isNull())).thenReturn(
                    page(null, "vid00000001", "vid00000002", "vid00000003"));
            List<Video> videos = List.of(
                    video("vid00000001", "UChidden", "none", "PT20M", 500L, null),
                    video("vid00000002", "UC1000", "none", "PT20M", 500L, null),
                    video("vid00000003", "UC1001", "none", "PT20M", 500L, null));
            when(client.fetchVideos(any())).thenReturn(videos);
            List<Channel> channels = List.of(
                    channel("UChidden", null, true),
                    channel("UC1000", 1000L, false),
                    channel("UC1001", 1001L, false));
            when(client.fetchChannels(any())).thenReturn(channels);
            Req req = new Req();
            req.maxSubscribers = 1000L;

            YouTubeSearchResponse response = service.search(req.build(), "alice");

            assertThat(videoIdsOf(response)).containsExactly("vid00000001", "vid00000002");
        }

        @Test
        @DisplayName("正常系：タイトルに含まないの語は、先頭に - を付けて書いても付けないときと同じく、その語を含む動画を落とす")
        void testMethod22() throws Exception {
            Video clip = video("vid00000001", CHANNEL_ID, "none", "PT20M", 500L, null);
            clip.getSnippet().setTitle("【切り抜き】歌枠まとめ");
            Video song = video("vid00000002", CHANNEL_ID, "none", "PT20M", 500L, null);
            song.getSnippet().setTitle("歌枠");
            when(client.searchVideoIds(any(), isNull())).thenReturn(page(null, "vid00000001", "vid00000002"));
            when(client.fetchVideos(any())).thenReturn(List.of(clip, song));
            Req req = new Req();

            req.titleExcludes = "-切り抜き";
            YouTubeSearchResponse dashed = service.search(req.build(), "alice");
            req.titleExcludes = "切り抜き";
            YouTubeSearchResponse plain = service.search(req.build(), "alice");

            assertThat(videoIdsOf(dashed)).containsExactly("vid00000002");
            assertThat(videoIdsOf(plain)).containsExactly("vid00000002");
        }

        @Test
        @DisplayName("正常系：タイトルの語は全角・半角、大文字・小文字をそろえて比べる")
        void testMethod23() throws Exception {
            Video fullWidth = video("vid00000001", CHANNEL_ID, "none", "PT20M", 500L, null);
            fullWidth.getSnippet().setTitle("【ＡＳＭＲ】耳かき");
            Video halfWidthKana = video("vid00000002", CHANNEL_ID, "none", "PT20M", 500L, null);
            halfWidthKana.getSnippet().setTitle("【ASMR】【ｶﾗｵｹ】歌枠");
            Video other = video("vid00000003", CHANNEL_ID, "none", "PT20M", 500L, null);
            other.getSnippet().setTitle("雑談");
            when(client.searchVideoIds(any(), isNull())).thenReturn(
                    page(null, "vid00000001", "vid00000002", "vid00000003"));
            when(client.fetchVideos(any())).thenReturn(List.of(fullWidth, halfWidthKana, other));
            Req req = new Req();
            req.titleIncludes = "asmr";
            req.titleExcludes = "カラオケ";

            YouTubeSearchResponse response = service.search(req.build(), "alice");

            // 全角の ＡＳＭＲ は asmr に一致して残る。半角カナの ｶﾗｵｹ はカラオケとして除外される。
            // 雑談は含む語に無いので落ちる
            assertThat(videoIdsOf(response)).containsExactly("vid00000001");
        }

        /** 後の要求が先の読み込みの終わりを待つ（{@code WAITING}）まで、最大 5 秒回る。 */
        private void awaitWaiting(Thread thread) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }
    }

    @Nested
    @DisplayName("findVideo()")
    class FindVideo {

        @Test
        @DisplayName("異常系：動画 ID の形が違えば API を呼ばずに空を返す")
        void testMethod01() {
            Optional<YouTubeVideoResponse> result = service.findVideo("abc", "alice");

            assertThat(result).isEmpty();
            verifyNoInteractions(client, budget);
        }

        @Test
        @DisplayName("正常系：見つかった動画は使い回し、2 回目は API も詳細の回数も使わない")
        void testMethod02() throws Exception {
            Video found = video("vid00000001", CHANNEL_ID, "none", "PT20M", 500L, null);
            when(client.fetchVideos(any())).thenReturn(List.of(found));

            Optional<YouTubeVideoResponse> first = service.findVideo("vid00000001", "alice");
            Optional<YouTubeVideoResponse> second = service.findVideo("vid00000001", "alice");

            assertThat(first).map(YouTubeVideoResponse::videoId).contains("vid00000001");
            assertThat(second).map(YouTubeVideoResponse::videoId).contains("vid00000001");
            verify(client, times(1)).fetchVideos(any());
            verify(budget, times(1)).acquireDetailForUser("alice");
        }

        @Test
        @DisplayName("異常系：403 の quotaExceeded なら今日の検索を止めて 503 にする")
        void testMethod03() throws Exception {
            when(client.fetchVideos(any())).thenThrow(apiError(403, "quotaExceeded"));

            assertThatThrownBy(() -> service.findVideo("vid00000001", "alice"))
                    .isInstanceOf(YouTubeApiUnavailableException.class)
                    .hasMessage("YouTube API の本日の上限に達しました");
            verify(budget, times(1)).markExhausted();
        }

        @Test
        @DisplayName("正常系：見つからなかった ID は覚え、続けて開いても API も詳細の回数も使わない")
        void testMethod04() throws Exception {
            when(client.fetchVideos(any())).thenReturn(List.of());

            Optional<YouTubeVideoResponse> first = service.findVideo("vid00000001", "alice");
            Optional<YouTubeVideoResponse> second = service.findVideo("vid00000001", "alice");

            assertThat(first).isEmpty();
            assertThat(second).isEmpty();
            verify(client, times(1)).fetchVideos(any());
            verify(budget, times(1)).acquireDetailForUser("alice");
        }

        @Test
        @DisplayName("正常系：API の失敗は覚えず、次に開くと API を呼び直す")
        void testMethod05() throws Exception {
            Video found = video("vid00000001", CHANNEL_ID, "none", "PT20M", 500L, null);
            when(client.fetchVideos(any())).thenThrow(new IOException("timeout")).thenReturn(List.of(found));

            assertThatThrownBy(() -> service.findVideo("vid00000001", "alice"))
                    .isInstanceOf(YouTubeApiUnavailableException.class);
            Optional<YouTubeVideoResponse> retried = service.findVideo("vid00000001", "alice");

            assertThat(retried).map(YouTubeVideoResponse::videoId).contains("vid00000001");
            verify(client, times(2)).fetchVideos(any());
        }

        @Test
        @DisplayName("異常系：詳細の回数の上限なら API を呼ばずに 429 にする")
        void testMethod06() {
            SearchQuotaExceededException exceeded = new SearchQuotaExceededException("詳細の上限");
            doThrow(exceeded).when(budget).acquireDetailForUser("alice");

            assertThatThrownBy(() -> service.findVideo("vid00000001", "alice")).isSameAs(exceeded);
            verifyNoInteractions(client);
        }
    }

    @Nested
    @DisplayName("searchCacheTtl()")
    class SearchCacheTtl {

        private Duration ttl(String eventType) {
            Req req = new Req();
            req.eventType = eventType;
            return ReflectionTestUtils.invokeMethod(service, "searchCacheTtl", req.build(), "alice");
        }

        @Test
        @DisplayName("正常系：ライブ中の検索は、その人の残りがあれば 15 分で取り直す")
        void testMethod01() {
            when(budget.status("alice")).thenReturn(new SearchQuotaStatus(40, 3, Instant.EPOCH));

            assertThat(ttl("live")).isEqualTo(Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("正常系：ライブ中の検索でも、その人の残りが 0 回なら 6 時間使い回す")
        void testMethod02() {
            when(budget.status("alice")).thenReturn(new SearchQuotaStatus(40, 0, Instant.EPOCH));

            assertThat(ttl("live")).isEqualTo(Duration.ofHours(6));
        }

        @Test
        @DisplayName("正常系：ライブ中以外の検索は、残りを見ずに 6 時間使い回す")
        void testMethod03() {
            assertThat(ttl("upcoming")).isEqualTo(Duration.ofHours(6));
            assertThat(ttl("completed")).isEqualTo(Duration.ofHours(6));
            assertThat(ttl(null)).isEqualTo(Duration.ofHours(6));
            verify(budget, never()).status(any());
        }
    }
}
