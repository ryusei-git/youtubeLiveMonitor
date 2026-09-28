package com.example.monitor.service;

import com.example.monitor.dto.DiscoveryCandidateResponse;
import com.example.monitor.dto.DiscoveryStatusResponse;
import com.example.monitor.entity.DiscoveryCandidate;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.exception.YouTubeApiUnavailableException;
import com.example.monitor.platform.youtube.YouTubeStreamPlatform;
import com.example.monitor.repository.DiscoveryCandidateRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.service.DiscoveryService.RunResult;
import com.example.monitor.service.DiscoveryYouTubeClient.SearchHit;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelContentDetails;
import com.google.api.services.youtube.model.ChannelSnippet;
import com.google.api.services.youtube.model.ChannelStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiscoveryService")
class DiscoveryServiceTest {

    /** 初回の検索で遡る幅（{@code DiscoveryService.FIRST_LOOKBACK}）。 */
    private static final Duration FIRST_LOOKBACK = Duration.ofHours(72);
    /** 前回検索できた語の次の回の始まり（前回の値からの差）。72 時間前から、前回の開始の 1 時間前へ進む。 */
    private static final Duration NEXT_WINDOW_SHIFT = Duration.ofHours(71);

    @Mock
    private DiscoveryCandidateRepository candidates;

    @Mock
    private MonitoredChannelRepository monitoredChannels;

    @Mock
    private DiscoveryYouTubeClient youtube;

    @Mock
    private YouTubeSearchBudget budget;

    @Mock
    private YouTubeStreamPlatform youTubePlatform;

    private DiscoveryService service;

    @BeforeEach
    void setUp() {
        // 語は 2 つ、登録者の上限 1000、動画の数の上限 10、取り直し 20 日、期限 30 日、定期の巡回なし
        service = new DiscoveryService(candidates, monitoredChannels, youtube, budget, youTubePlatform,
                "語A,語B", "VTuber,新人", 1000, 10, 20, 30, false, "-");
    }

    private static Channel channel(String id, Long subscribers, boolean hidden, long videos, String description) {
        ChannelStatistics statistics = new ChannelStatistics()
                .setHiddenSubscriberCount(hidden)
                .setVideoCount(BigInteger.valueOf(videos));
        if (subscribers != null) {
            statistics.setSubscriberCount(BigInteger.valueOf(subscribers));
        }
        return new Channel()
                .setId(id)
                .setSnippet(new ChannelSnippet().setTitle("チャンネル " + id).setDescription(description))
                .setStatistics(statistics);
    }

    /** アップロードの再生リストを付ける（付けたチャンネルだけ、最初の投稿日を引く）。 */
    private static Channel withUploads(Channel channel, String uploadsPlaylistId) {
        return channel.setContentDetails(new ChannelContentDetails().setRelatedPlaylists(
                new ChannelContentDetails.RelatedPlaylists().setUploads(uploadsPlaylistId)));
    }

    /** API の値と判定が入った候補の行。 */
    private static DiscoveryCandidate candidate(String channelId, DiscoveryCandidate.Status status, Instant refreshedAt) {
        DiscoveryCandidate candidate = new DiscoveryCandidate();
        candidate.setChannelId(channelId);
        candidate.setStatus(status);
        candidate.setTitle("古いタイトル " + channelId);
        candidate.setDescription("古い説明");
        candidate.setDiscoveredAt(refreshedAt);
        candidate.setRefreshedAt(refreshedAt);
        return candidate;
    }

    /** 「ちがう」と判定して API の値を消した行（{@code refreshedAt} が null）。 */
    private static DiscoveryCandidate clearedCandidate(String channelId) {
        DiscoveryCandidate candidate = new DiscoveryCandidate();
        candidate.setChannelId(channelId);
        candidate.setStatus(DiscoveryCandidate.Status.REJECTED);
        candidate.setDecidedBy("bob");
        candidate.setDecidedAt(Instant.parse("2026-09-01T00:00:00Z"));
        return candidate;
    }

    private static GoogleJsonResponseException apiError(int status, String reason) {
        GoogleJsonError.ErrorInfo info = new GoogleJsonError.ErrorInfo();
        info.setReason(reason);
        GoogleJsonError details = new GoogleJsonError();
        details.setCode(status);
        details.setErrors(List.of(info));
        return new GoogleJsonResponseException(new HttpResponseException.Builder(status, null, new HttpHeaders()), details);
    }

    /** {@code channels()} が、渡された ID のうち用意したチャンネルだけを（渡された順に）返すようにする。 */
    private void stubChannels(Channel... prepared) throws IOException {
        Map<String, Channel> byId = Arrays.stream(prepared)
                .collect(Collectors.toMap(Channel::getId, Function.identity()));
        when(youtube.channels(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        });
    }

    /** {@code findById()} が、渡した行のうち同じチャンネル ID の行（同じインスタンス）を返すようにする。 */
    private void stubFindByIdFrom(List<DiscoveryCandidate> rows) {
        when(candidates.findById(anyString())).thenAnswer(invocation -> rows.stream()
                .filter(row -> row.getChannelId().equals(invocation.getArgument(0)))
                .findFirst());
    }

    private void stubSaveReturnsArgument() {
        when(candidates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * {@code searchRecentVideos()} に渡った {@code publishedAfter} のうち、指定の語のものを呼ばれた順に返す。
     *
     * @param term       検索語
     * @param totalCalls 全部の語を合わせた呼び出しの回数
     */
    private List<Instant> publishedAfterOf(String term, int totalCalls) throws IOException {
        ArgumentCaptor<String> terms = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> afters = ArgumentCaptor.forClass(Instant.class);
        verify(youtube, times(totalCalls)).searchRecentVideos(terms.capture(), afters.capture());
        List<Instant> result = new ArrayList<>();
        for (int i = 0; i < terms.getAllValues().size(); i++) {
            if (term.equals(terms.getAllValues().get(i))) {
                result.add(afters.getAllValues().get(i));
            }
        }
        return result;
    }

    @Nested
    @DisplayName("run()")
    class Run {

        @Test
        @DisplayName("正常系：語ごとに回数を取ってから検索し、使った回数を返す")
        void testMethod01() throws Exception {
            when(budget.tryAcquireForDiscovery()).thenReturn(true);
            when(youtube.searchRecentVideos(anyString(), any())).thenReturn(List.of());

            RunResult result = service.run().orElseThrow();

            assertThat(result.searches()).isEqualTo(2);
            InOrder order = inOrder(budget, youtube);
            order.verify(budget).tryAcquireForDiscovery();
            order.verify(youtube).searchRecentVideos(eq("語A"), any());
            order.verify(budget).tryAcquireForDiscovery();
            order.verify(youtube).searchRecentVideos(eq("語B"), any());
        }

        @Test
        @DisplayName("異常系：発掘の枠に達した語で打ち切り、残りの語は検索しない")
        void testMethod02() throws Exception {
            when(budget.tryAcquireForDiscovery()).thenReturn(true, false);
            when(youtube.searchRecentVideos(anyString(), any())).thenReturn(List.of());

            RunResult result = service.run().orElseThrow();

            assertThat(result.searches()).isEqualTo(1);
            verify(youtube, times(1)).searchRecentVideos(anyString(), any());
            verify(youtube, never()).searchRecentVideos(eq("語B"), any());
        }

        @Test
        @DisplayName("異常系：quotaExceeded なら今日の検索を止め、次の語は回数も取らない")
        void testMethod03() throws Exception {
            when(budget.tryAcquireForDiscovery()).thenReturn(true);
            when(youtube.searchRecentVideos(anyString(), any())).thenThrow(apiError(403, "quotaExceeded"));

            service.run();

            verify(budget, times(1)).markExhausted();
            verify(budget, times(1)).tryAcquireForDiscovery();
            verify(youtube, never()).searchRecentVideos(eq("語B"), any());
        }

        @Test
        @DisplayName("異常系：ほかの失敗はその語だけ飛ばして次の語へ進む")
        void testMethod04() throws Exception {
            when(budget.tryAcquireForDiscovery()).thenReturn(true);
            GoogleJsonResponseException backendError = apiError(500, "backendError");
            when(youtube.searchRecentVideos(anyString(), any())).thenAnswer(invocation -> {
                if ("語A".equals(invocation.getArgument(0))) {
                    throw backendError;
                }
                return List.of();
            });

            RunResult result = service.run().orElseThrow();

            assertThat(result.searches()).isEqualTo(2);
            verify(youtube).searchRecentVideos(eq("語B"), any());
            verify(budget, never()).markExhausted();
        }

        @Test
        @DisplayName("正常系：監視中・候補にあるチャンネルは引かず、登録者・動画の数・語の条件に合うチャンネルだけ候補にする")
        void testMethod05() throws Exception {
            when(budget.tryAcquireForDiscovery()).thenReturn(true);
            List<SearchHit> hits = List.of(
                    new SearchHit("vid00000001", "ch1", "監視中の配信"),
                    new SearchHit("vid00000002", "ch2", "候補にある配信"),
                    new SearchHit("vid00000003", "ch3", "歌ってみた"),
                    new SearchHit("vid00000004", "ch4", "新人VTuberの歌"),
                    new SearchHit("vid00000005", "ch5", "【VTuber】初配信"),
                    new SearchHit("vid00000006", "ch6", "今日の料理"));
            // 語B は空にする（同じ 6 件を返すと、ch3・ch5 がもう一度保存されて saved が 4 になる）
            when(youtube.searchRecentVideos(anyString(), any()))
                    .thenAnswer(invocation -> "語A".equals(invocation.getArgument(0)) ? hits : List.of());
            List<MonitoredChannel> monitored = List.of(new MonitoredChannel("ch1", "監視中のチャンネル"));
            when(monitoredChannels.findAll()).thenReturn(monitored);
            when(candidates.existsById(anyString())).thenAnswer(invocation -> "ch2".equals(invocation.getArgument(0)));
            stubChannels(
                    channel("ch1", 500L, false, 3, "新人VTuber"),
                    channel("ch2", 500L, false, 3, "新人VTuber"),
                    channel("ch3", 500L, false, 3, "新人VTuberです"),
                    channel("ch4", 5000L, false, 3, "新人VTuber"),
                    channel("ch5", null, true, 3, ""),
                    channel("ch6", 500L, false, 3, "料理のチャンネル"));

            RunResult result = service.run().orElseThrow();

            assertThat(result.saved()).isEqualTo(2);
            verify(youtube).channels(List.of("ch3", "ch4", "ch5", "ch6"));
            ArgumentCaptor<DiscoveryCandidate> saved = ArgumentCaptor.forClass(DiscoveryCandidate.class);
            verify(candidates, times(2)).save(saved.capture());
            assertThat(saved.getAllValues())
                    .extracting(DiscoveryCandidate::getChannelId, DiscoveryCandidate::getFoundByTerm,
                            DiscoveryCandidate::getSampleVideoId, DiscoveryCandidate::getMatchedWords)
                    .containsExactly(
                            tuple("ch3", "語A", "vid00000003", "VTuber,新人"),
                            tuple("ch5", "語A", "vid00000005", "VTuber"));
        }

        @Test
        @DisplayName("正常系：初回はどの語も巡回の開始の 72 時間前から探す")
        void testMethod06() throws Exception {
            when(budget.tryAcquireForDiscovery()).thenReturn(true);
            when(youtube.searchRecentVideos(anyString(), any())).thenReturn(List.of());

            Instant calledFrom = Instant.now();
            service.run();
            Instant calledUntil = Instant.now();

            List<Instant> termA = publishedAfterOf("語A", 2);
            List<Instant> termB = publishedAfterOf("語B", 2);
            assertThat(termA).hasSize(1);
            assertThat(termB).hasSize(1);
            assertThat(termA.get(0)).isBetween(calledFrom.minus(FIRST_LOOKBACK), calledUntil.minus(FIRST_LOOKBACK));
            assertThat(termB.get(0)).isBetween(calledFrom.minus(FIRST_LOOKBACK), calledUntil.minus(FIRST_LOOKBACK));
        }

        @Test
        @DisplayName("正常系：検索できた語は、次の回に前回の開始の 1 時間前から探す")
        void testMethod07() throws Exception {
            when(budget.tryAcquireForDiscovery()).thenReturn(true);
            when(youtube.searchRecentVideos(anyString(), any())).thenReturn(List.of());

            service.run();
            service.run();

            List<Instant> termA = publishedAfterOf("語A", 4);
            List<Instant> termB = publishedAfterOf("語B", 4);
            assertThat(termA).hasSize(2);
            assertThat(termB).hasSize(2);
            assertThat(termA.get(1)).isEqualTo(termA.get(0).plus(NEXT_WINDOW_SHIFT));
            assertThat(termB.get(1)).isEqualTo(termB.get(0).plus(NEXT_WINDOW_SHIFT));
        }

        @Test
        @DisplayName("正常系：打ち切られた語は時刻を進めず、次の回に先に回す")
        void testMethod08() throws Exception {
            when(budget.tryAcquireForDiscovery()).thenReturn(true, false, true, true);
            when(youtube.searchRecentVideos(anyString(), any())).thenReturn(List.of());

            service.run();
            Instant secondFrom = Instant.now();
            service.run();
            Instant secondUntil = Instant.now();

            InOrder order = inOrder(youtube);
            order.verify(youtube).searchRecentVideos(eq("語A"), any());   // 1 回目（語B は打ち切り）
            order.verify(youtube).searchRecentVideos(eq("語B"), any());   // 2 回目は打ち切られた語から
            order.verify(youtube).searchRecentVideos(eq("語A"), any());
            List<Instant> termA = publishedAfterOf("語A", 3);
            List<Instant> termB = publishedAfterOf("語B", 3);
            assertThat(termB).hasSize(1);
            assertThat(termB.get(0)).isBetween(secondFrom.minus(FIRST_LOOKBACK), secondUntil.minus(FIRST_LOOKBACK));
            assertThat(termA).hasSize(2);
            assertThat(termA.get(1)).isEqualTo(termA.get(0).plus(NEXT_WINDOW_SHIFT));
        }

        @Test
        @DisplayName("正常系：失敗した語は時刻を進めない")
        void testMethod09() throws Exception {
            when(budget.tryAcquireForDiscovery()).thenReturn(true);
            GoogleJsonResponseException backendError = apiError(500, "backendError");
            AtomicBoolean failed = new AtomicBoolean();
            when(youtube.searchRecentVideos(anyString(), any())).thenAnswer(invocation -> {
                // 1 回目の巡回の語A だけ失敗させる
                if ("語A".equals(invocation.getArgument(0)) && failed.compareAndSet(false, true)) {
                    throw backendError;
                }
                return List.of();
            });

            service.run();
            Instant secondFrom = Instant.now();
            service.run();
            Instant secondUntil = Instant.now();

            List<Instant> termA = publishedAfterOf("語A", 4);
            List<Instant> termB = publishedAfterOf("語B", 4);
            assertThat(termA).hasSize(2);
            assertThat(termB).hasSize(2);
            assertThat(termA.get(1)).isBetween(secondFrom.minus(FIRST_LOOKBACK), secondUntil.minus(FIRST_LOOKBACK));
            assertThat(termB.get(1)).isEqualTo(termB.get(0).plus(NEXT_WINDOW_SHIFT));
        }
    }

    @Nested
    @DisplayName("status()")
    class Status {

        @Test
        @DisplayName("正常系：一度も巡回していなければ前回の時刻と回数は null")
        void testMethod01() {
            DiscoveryStatusResponse status = service.status();

            assertThat(status.lastRunAt()).isNull();
            assertThat(status.lastRunSearches()).isNull();
        }

        @Test
        @DisplayName("正常系：1 語も検索できなかった巡回は、回数 0 として時刻を残す")
        void testMethod02() {
            when(budget.tryAcquireForDiscovery()).thenReturn(false);

            service.run();
            DiscoveryStatusResponse status = service.status();

            assertThat(status.lastRunSearches()).isZero();
            assertThat(status.lastRunAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("sweep()")
    class Sweep {

        /** 取り直しの時期（20 日）を過ぎた行の取り直した時刻。 */
        private final Instant staleRefreshedAt = Instant.now().minus(Duration.ofDays(25));

        @Test
        @DisplayName("正常系：判定されないまま期限を過ぎた候補を消す")
        void testMethod01() {
            List<DiscoveryCandidate> expired = List.of(candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt));
            when(candidates.findByStatusAndDiscoveredAtBefore(eq(DiscoveryCandidate.Status.CANDIDATE), any())).thenReturn(expired);

            Instant calledFrom = Instant.now();
            service.sweep();
            Instant calledUntil = Instant.now();

            ArgumentCaptor<Instant> before = ArgumentCaptor.forClass(Instant.class);
            verify(candidates).findByStatusAndDiscoveredAtBefore(eq(DiscoveryCandidate.Status.CANDIDATE), before.capture());
            assertThat(before.getValue())
                    .isBetween(calledFrom.minus(Duration.ofDays(30)), calledUntil.minus(Duration.ofDays(30)));
            verify(candidates).deleteAll(expired);
        }

        @Test
        @DisplayName("正常系：取り直しの時期の行は値を入れ直して保存し、消えたチャンネルの行は消す")
        void testMethod02() throws Exception {
            DiscoveryCandidate kept = candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt);
            DiscoveryCandidate gone = candidate("ch2", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt);
            List<DiscoveryCandidate> stale = List.of(kept, gone);
            when(candidates.findByStatusInAndRefreshedAtBefore(any(), any())).thenReturn(stale);
            stubChannels(channel("ch1", 500L, false, 3, "新しい説明"));
            stubFindByIdFrom(stale);

            Instant calledFrom = Instant.now();
            service.sweep();
            Instant calledUntil = Instant.now();

            assertThat(kept.getRefreshedAt()).isBetween(calledFrom, calledUntil);
            assertThat(kept.getTitle()).isEqualTo("チャンネル ch1");
            verify(candidates).save(kept);
            verify(candidates, never()).delete(kept);
            verify(candidates).delete(gone);
            verify(candidates, never()).save(gone);
        }

        @Test
        @DisplayName("正常系：見回りの間に判定が変わった行は書き戻さない")
        void testMethod03() throws Exception {
            DiscoveryCandidate read = candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt);
            when(candidates.findByStatusInAndRefreshedAtBefore(any(), any())).thenReturn(List.of(read));
            stubChannels(channel("ch1", 500L, false, 3, "新しい説明"));
            DiscoveryCandidate decided = candidate("ch1", DiscoveryCandidate.Status.REJECTED, staleRefreshedAt);
            when(candidates.findById(anyString())).thenReturn(Optional.of(decided));

            service.sweep();

            verify(candidates, never()).save(any());
            verify(candidates, never()).delete(any());
        }

        @Test
        @DisplayName("正常系：見回りの間に取り直した時刻が変わった行は、状態が同じでも書き戻さない")
        void testMethod04() throws Exception {
            DiscoveryCandidate read = candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt);
            when(candidates.findByStatusInAndRefreshedAtBefore(any(), any())).thenReturn(List.of(read));
            stubChannels(channel("ch1", 500L, false, 3, "新しい説明"));
            DiscoveryCandidate readded = candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt.plus(Duration.ofDays(1)));
            when(candidates.findById(anyString())).thenReturn(Optional.of(readded));

            service.sweep();

            verify(candidates, never()).save(any());
            verify(candidates, never()).delete(any());
        }

        @Test
        @DisplayName("異常系：取り直しで API が失敗したら、消しも保存もしない")
        void testMethod05() throws Exception {
            List<DiscoveryCandidate> expired = List.of(candidate("ch9", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt));
            when(candidates.findByStatusAndDiscoveredAtBefore(eq(DiscoveryCandidate.Status.CANDIDATE), any())).thenReturn(expired);
            List<DiscoveryCandidate> stale = List.of(
                    candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt),
                    candidate("ch2", DiscoveryCandidate.Status.VTUBER, staleRefreshedAt));
            when(candidates.findByStatusInAndRefreshedAtBefore(any(), any())).thenReturn(stale);
            when(youtube.channels(anyList())).thenThrow(new IOException("timeout"));

            service.sweep();

            verify(candidates).deleteAll(expired);
            verify(candidates, never()).delete(any());
            verify(candidates, never()).save(any());
        }

        @Test
        @DisplayName("正常系：見つけた動画のタイトルを取り直し、消えた動画は値を消す")
        void testMethod06() throws Exception {
            DiscoveryCandidate renamed = candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt);
            renamed.setSampleVideoId("vid00000001");
            renamed.setSampleVideoTitle("古い動画のタイトル");
            DiscoveryCandidate removed = candidate("ch2", DiscoveryCandidate.Status.VTUBER, staleRefreshedAt);
            removed.setSampleVideoId("vid00000002");
            removed.setSampleVideoTitle("消えた動画のタイトル");
            List<DiscoveryCandidate> stale = List.of(renamed, removed);
            when(candidates.findByStatusInAndRefreshedAtBefore(any(), any())).thenReturn(stale);
            stubChannels(channel("ch1", 500L, false, 3, "説明"), channel("ch2", 500L, false, 3, "説明"));
            when(youtube.videoTitles(anyList())).thenReturn(Map.of("vid00000001", "新しい動画のタイトル"));
            stubFindByIdFrom(stale);

            service.sweep();

            verify(youtube).videoTitles(List.of("vid00000001", "vid00000002"));
            assertThat(renamed.getSampleVideoId()).isEqualTo("vid00000001");
            assertThat(renamed.getSampleVideoTitle()).isEqualTo("新しい動画のタイトル");
            assertThat(removed.getSampleVideoId()).isNull();
            assertThat(removed.getSampleVideoTitle()).isNull();
            verify(candidates).save(renamed);
            verify(candidates).save(removed);
        }

        @Test
        @DisplayName("正常系：最初の投稿日を取り直す")
        void testMethod07() throws Exception {
            DiscoveryCandidate row = candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt);
            row.setFirstUploadAt(Instant.parse("2026-07-01T00:00:00Z"));
            List<DiscoveryCandidate> stale = List.of(row);
            when(candidates.findByStatusInAndRefreshedAtBefore(any(), any())).thenReturn(stale);
            stubChannels(withUploads(channel("ch1", 500L, false, 3, "説明"), "UUch1"));
            Instant firstUpload = Instant.parse("2026-08-01T00:00:00Z");
            when(youtube.oldestUpload("UUch1")).thenReturn(Optional.of(firstUpload));
            stubFindByIdFrom(stale);

            service.sweep();

            assertThat(row.getFirstUploadAt()).isEqualTo(firstUpload);
            verify(candidates).save(row);
        }

        @Test
        @DisplayName("異常系：最初の投稿日で quotaExceeded なら今日の検索を止め、その行と残りの行は保存しない")
        void testMethod08() throws Exception {
            List<DiscoveryCandidate> stale = List.of(
                    candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt),
                    candidate("ch2", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt));
            when(candidates.findByStatusInAndRefreshedAtBefore(any(), any())).thenReturn(stale);
            stubChannels(
                    withUploads(channel("ch1", 500L, false, 3, "説明"), "UUch1"),
                    withUploads(channel("ch2", 500L, false, 3, "説明"), "UUch2"));
            when(youtube.oldestUpload(anyString())).thenThrow(apiError(403, "quotaExceeded"));

            service.sweep();

            verify(budget, times(1)).markExhausted();
            verify(candidates, never()).save(any());
        }

        @Test
        @DisplayName("異常系：動画のタイトルの取り直しが失敗したら、消しも保存もしない")
        void testMethod09() throws Exception {
            DiscoveryCandidate found = candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt);
            found.setSampleVideoId("vid00000001");
            DiscoveryCandidate missing = candidate("ch2", DiscoveryCandidate.Status.CANDIDATE, staleRefreshedAt);
            when(candidates.findByStatusInAndRefreshedAtBefore(any(), any())).thenReturn(List.of(found, missing));
            // ch2 は返さない（先へ進めば消す行）
            stubChannels(channel("ch1", 500L, false, 3, "説明"));
            when(youtube.videoTitles(anyList())).thenThrow(new IOException("timeout"));

            service.sweep();

            verify(candidates, never()).delete(any());
            verify(candidates, never()).save(any());
        }
    }

    @Nested
    @DisplayName("decide()")
    class Decide {

        @Test
        @DisplayName("正常系：「ちがう」にすると API の値を消し、判定した人と日時を入れる")
        void testMethod01() {
            DiscoveryCandidate row = candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, Instant.now().minus(Duration.ofDays(1)));
            when(candidates.findById("ch1")).thenReturn(Optional.of(row));
            stubSaveReturnsArgument();

            Instant calledFrom = Instant.now();
            DiscoveryCandidateResponse response = service.decide("ch1", "REJECTED", "alice");
            Instant calledUntil = Instant.now();

            assertThat(response.status()).isEqualTo("REJECTED");
            assertThat(row.getTitle()).isNull();
            assertThat(row.getRefreshedAt()).isNull();
            assertThat(row.getDiscoveredAt()).isNull();
            assertThat(row.getDecidedBy()).isEqualTo("alice");
            assertThat(row.getDecidedAt()).isBetween(calledFrom, calledUntil);
            verifyNoInteractions(youtube);
        }

        @Test
        @DisplayName("正常系：値を消した行を VTuber にすると、チャンネルを取り直して見つけた日時を入れる")
        void testMethod02() throws Exception {
            DiscoveryCandidate row = clearedCandidate("ch1");
            when(candidates.findById("ch1")).thenReturn(Optional.of(row));
            stubChannels(channel("ch1", 500L, false, 3, "説明"));
            stubSaveReturnsArgument();

            Instant calledFrom = Instant.now();
            service.decide("ch1", "VTUBER", "alice");
            Instant calledUntil = Instant.now();

            verify(youtube, times(1)).channels(anyList());
            assertThat(row.getTitle()).isEqualTo("チャンネル ch1");
            assertThat(row.getDiscoveredAt()).isBetween(calledFrom, calledUntil);
            assertThat(row.getStatus()).isEqualTo(DiscoveryCandidate.Status.VTUBER);
        }

        @Test
        @DisplayName("正常系：候補に戻すと判定した人と日時を消す")
        void testMethod03() {
            DiscoveryCandidate row = candidate("ch1", DiscoveryCandidate.Status.VTUBER, Instant.now().minus(Duration.ofDays(1)));
            row.setDecidedBy("bob");
            row.setDecidedAt(Instant.now().minus(Duration.ofDays(1)));
            when(candidates.findById("ch1")).thenReturn(Optional.of(row));
            stubSaveReturnsArgument();

            service.decide("ch1", "CANDIDATE", "alice");

            assertThat(row.getStatus()).isEqualTo(DiscoveryCandidate.Status.CANDIDATE);
            assertThat(row.getDecidedBy()).isNull();
            assertThat(row.getDecidedAt()).isNull();
        }

        @Test
        @DisplayName("正常系：VTuber を候補に戻すと見つけた日時を今にする")
        void testMethod04() {
            DiscoveryCandidate row = candidate("ch1", DiscoveryCandidate.Status.VTUBER, Instant.now().minus(Duration.ofDays(1)));
            row.setDiscoveredAt(Instant.now().minus(Duration.ofDays(40)));
            when(candidates.findById("ch1")).thenReturn(Optional.of(row));
            stubSaveReturnsArgument();

            Instant calledFrom = Instant.now();
            service.decide("ch1", "CANDIDATE", "alice");
            Instant calledUntil = Instant.now();

            assertThat(row.getDiscoveredAt()).isBetween(calledFrom, calledUntil);
        }

        @Test
        @DisplayName("異常系：取り直しで YouTube が失敗したら 503 にして保存しない")
        void testMethod05() throws Exception {
            DiscoveryCandidate row = clearedCandidate("ch1");
            when(candidates.findById("ch1")).thenReturn(Optional.of(row));
            when(youtube.channels(anyList())).thenThrow(new IOException("timeout"));

            assertThatThrownBy(() -> service.decide("ch1", "VTUBER", "alice"))
                    .isInstanceOf(YouTubeApiUnavailableException.class)
                    .hasMessage("YouTube からチャンネルを取得できませんでした");
            verify(candidates, never()).save(any());
            verify(budget, never()).markExhausted();
        }

        @Test
        @DisplayName("異常系：取り直しで quotaExceeded なら今日の検索を止めて 503 にする")
        void testMethod06() throws Exception {
            DiscoveryCandidate row = clearedCandidate("ch1");
            when(candidates.findById("ch1")).thenReturn(Optional.of(row));
            when(youtube.channels(anyList())).thenThrow(apiError(403, "quotaExceeded"));

            assertThatThrownBy(() -> service.decide("ch1", "VTUBER", "alice"))
                    .isInstanceOf(YouTubeApiUnavailableException.class)
                    .hasMessage("YouTube API の本日の上限に達しました");
            verify(budget, times(1)).markExhausted();
            verify(candidates, never()).save(any());
        }
    }

    @Nested
    @DisplayName("add()")
    class Add {

        private static final String INPUT = "https://www.youtube.com/channel/ch1";

        @Test
        @DisplayName("正常系：検索の回数を使わずに登録し、「ちがう」だった行は候補に戻す")
        void testMethod01() throws Exception {
            when(youTubePlatform.normalizeChannelInput(INPUT)).thenReturn("ch1");
            stubChannels(channel("ch1", 500L, false, 3, "説明"));
            DiscoveryCandidate row = clearedCandidate("ch1");
            when(candidates.findById("ch1")).thenReturn(Optional.of(row));
            stubSaveReturnsArgument();

            DiscoveryCandidateResponse response = service.add(INPUT);

            assertThat(response.status()).isEqualTo("CANDIDATE");
            assertThat(row.getStatus()).isEqualTo(DiscoveryCandidate.Status.CANDIDATE);
            assertThat(row.getDecidedBy()).isNull();
            assertThat(row.getDecidedAt()).isNull();
            verify(budget, never()).tryAcquireForDiscovery();
            verify(budget, never()).acquireForUser(any());
        }

        @Test
        @DisplayName("正常系：既にある行を足し直すと、最初の投稿日と見つけた動画のタイトルを取り直す")
        void testMethod02() throws Exception {
            when(youTubePlatform.normalizeChannelInput(INPUT)).thenReturn("ch1");
            stubChannels(withUploads(channel("ch1", 500L, false, 3, "説明"), "UUch1"));
            DiscoveryCandidate row = candidate("ch1", DiscoveryCandidate.Status.CANDIDATE, Instant.now().minus(Duration.ofDays(1)));
            row.setFirstUploadAt(Instant.parse("2026-07-01T00:00:00Z"));
            row.setSampleVideoId("vid00000001");
            row.setSampleVideoTitle("古い動画のタイトル");
            when(candidates.findById("ch1")).thenReturn(Optional.of(row));
            Instant firstUpload = Instant.parse("2026-08-01T00:00:00Z");
            when(youtube.oldestUpload("UUch1")).thenReturn(Optional.of(firstUpload));
            when(youtube.videoTitles(anyList())).thenReturn(Map.of("vid00000001", "新しい動画のタイトル"));
            stubSaveReturnsArgument();

            service.add(INPUT);

            verify(youtube).videoTitles(List.of("vid00000001"));
            assertThat(row.getFirstUploadAt()).isEqualTo(firstUpload);
            assertThat(row.getSampleVideoTitle()).isEqualTo("新しい動画のタイトル");
        }

        @Test
        @DisplayName("異常系：YouTube の quotaExceeded なら今日の検索を止めて 503 にし、保存しない")
        void testMethod03() throws Exception {
            when(youTubePlatform.normalizeChannelInput(INPUT)).thenReturn("ch1");
            when(youtube.channels(anyList())).thenThrow(apiError(403, "quotaExceeded"));

            assertThatThrownBy(() -> service.add(INPUT)).isInstanceOf(YouTubeApiUnavailableException.class);
            verify(budget, times(1)).markExhausted();
            verify(candidates, never()).save(any());
        }
    }
}
