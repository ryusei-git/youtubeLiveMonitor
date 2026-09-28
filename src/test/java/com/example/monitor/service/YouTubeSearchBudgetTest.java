package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.entity.VideoCollectionQuota;
import com.example.monitor.exception.SearchQuotaExceededException;
import com.example.monitor.repository.VideoCollectionQuotaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("YouTubeSearchBudget")
class YouTubeSearchBudgetTest {

    /** 特に書いていないテストの時刻（太平洋夏時間の 2026-09-27 05:00）。 */
    private static final String NOON_UTC = "2026-09-27T12:00:00Z";
    /** {@link #NOON_UTC} の太平洋時間の日付。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 27);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 9, 26);

    private static final String TOTAL = "youtube-search";
    private static final String INTERACTIVE = "youtube-search-interactive";
    private static final String DISCOVERY = "youtube-search-discovery";
    private static final String ALICE = "youtube-search-user:alice";
    private static final String BOB = "youtube-search-user:bob";
    private static final String DETAIL_TOTAL = "youtube-detail";
    private static final String DETAIL_ALICE = "youtube-detail-user:alice";

    @Mock
    private VideoCollectionQuotaRepository repository;

    /** DB の代わり（行の id → 行）。 */
    private final Map<String, VideoCollectionQuota> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(repository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            VideoCollectionQuota row = invocation.getArgument(0);
            rows.put(row.getId(), row);
            return row;
        });
    }

    /** 既定の上限（1 日 52・発掘 12・1 人 10）で、指定の時刻に止めた予算を作る。 */
    private YouTubeSearchBudget budgetAt(String instant) {
        return budgetAt(instant, new MonitorProperties.SearchProperties(52, 12, 10));
    }

    private YouTubeSearchBudget budgetAt(String instant, MonitorProperties.SearchProperties limits) {
        MonitorProperties properties = new MonitorProperties(
                new MonitorProperties.YouTubeProperties("test-key", 120, limits), null, null, null, null);
        return new YouTubeSearchBudget(repository, properties, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private void putRow(String id, LocalDate quotaDate, int requests) {
        VideoCollectionQuota row = new VideoCollectionQuota();
        row.setId(id);
        row.setQuotaDate(quotaDate);
        row.setRequests(requests);
        rows.put(id, row);
    }

    private int requestsOf(String id) {
        return rows.get(id).getRequests();
    }

    @Nested
    @DisplayName("acquireForUser()")
    class AcquireForUser {

        @Test
        @DisplayName("正常系：全体・対話・その利用者の 3 行を 1 ずつ増やし、太平洋時間の今日の日付を入れる")
        void testMethod01() {
            budgetAt(NOON_UTC).acquireForUser("alice");

            assertThat(rows).containsOnlyKeys(TOTAL, INTERACTIVE, ALICE);
            assertThat(rows.values()).allSatisfy(row -> {
                assertThat(row.getRequests()).isEqualTo(1);
                assertThat(row.getQuotaDate()).isEqualTo(TODAY);
            });
        }

        @Test
        @DisplayName("異常系：その利用者の行が 1 人の上限なら例外にし、どの行も増やさない")
        void testMethod02() {
            putRow(ALICE, TODAY, 10);
            YouTubeSearchBudget budget = budgetAt(NOON_UTC);

            assertThatThrownBy(() -> budget.acquireForUser("alice")).isInstanceOf(SearchQuotaExceededException.class);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("異常系：対話の行が上限（1 日の上限 − 発掘の枠）なら例外にし、どの行も増やさない")
        void testMethod03() {
            putRow(INTERACTIVE, TODAY, 40);
            YouTubeSearchBudget budget = budgetAt(NOON_UTC);

            assertThatThrownBy(() -> budget.acquireForUser("alice")).isInstanceOf(SearchQuotaExceededException.class);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("異常系：全体の行が 1 日の上限なら例外にし、どの行も増やさない")
        void testMethod04() {
            putRow(TOTAL, TODAY, 52);
            YouTubeSearchBudget budget = budgetAt(NOON_UTC);

            assertThatThrownBy(() -> budget.acquireForUser("alice")).isInstanceOf(SearchQuotaExceededException.class);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("正常系：ほかの利用者の回数は数えない")
        void testMethod05() {
            putRow(ALICE, TODAY, 10);

            budgetAt(NOON_UTC).acquireForUser("bob");

            assertThat(requestsOf(BOB)).isEqualTo(1);
            assertThat(requestsOf(ALICE)).isEqualTo(10);
        }

        @Test
        @DisplayName("異常系：発掘の枠が 1 日の上限以上の設定では、対話の上限を 0 として例外にする")
        void testMethod06() {
            YouTubeSearchBudget budget = budgetAt(NOON_UTC, new MonitorProperties.SearchProperties(10, 20, 10));

            assertThatThrownBy(() -> budget.acquireForUser("alice")).isInstanceOf(SearchQuotaExceededException.class);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("正常系：太平洋夏時間の 0 時（日本時間 16 時）の 1 秒前までは前日の回数で止め、0 時からは数え直す")
        void testMethod07() {
            putRow(ALICE, YESTERDAY, 10);
            YouTubeSearchBudget beforeMidnight = budgetAt("2026-09-27T06:59:59Z");

            assertThatThrownBy(() -> beforeMidnight.acquireForUser("alice"))
                    .isInstanceOf(SearchQuotaExceededException.class);

            budgetAt("2026-09-27T07:00:00Z").acquireForUser("alice");

            assertThat(rows.get(ALICE).getQuotaDate()).isEqualTo(TODAY);
            assertThat(requestsOf(ALICE)).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系：太平洋標準時（冬）は 0 時が日本時間 17 時になる")
        void testMethod08() {
            putRow(ALICE, LocalDate.of(2026, 11, 30), 10);
            YouTubeSearchBudget beforeMidnight = budgetAt("2026-12-01T07:59:59Z");

            assertThatThrownBy(() -> beforeMidnight.acquireForUser("alice"))
                    .isInstanceOf(SearchQuotaExceededException.class);

            budgetAt("2026-12-01T08:00:00Z").acquireForUser("alice");

            assertThat(rows.get(ALICE).getQuotaDate()).isEqualTo(LocalDate.of(2026, 12, 1));
            assertThat(requestsOf(ALICE)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("tryAcquireForDiscovery()")
    class TryAcquireForDiscovery {

        @Test
        @DisplayName("正常系：全体と発掘の行を 1 ずつ増やして true を返し、対話の行は作らない")
        void testMethod01() {
            boolean acquired = budgetAt(NOON_UTC).tryAcquireForDiscovery();

            assertThat(acquired).isTrue();
            assertThat(rows).containsOnlyKeys(TOTAL, DISCOVERY);
            assertThat(requestsOf(TOTAL)).isEqualTo(1);
            assertThat(requestsOf(DISCOVERY)).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系：発掘の行が発掘の枠に達していたら false を返し、どの行も増やさない")
        void testMethod02() {
            putRow(DISCOVERY, TODAY, 12);

            boolean acquired = budgetAt(NOON_UTC).tryAcquireForDiscovery();

            assertThat(acquired).isFalse();
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("異常系：全体の行が 1 日の上限なら false を返し、どの行も増やさない")
        void testMethod03() {
            putRow(TOTAL, TODAY, 52);

            boolean acquired = budgetAt(NOON_UTC).tryAcquireForDiscovery();

            assertThat(acquired).isFalse();
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("正常系：対話の行が上限でも、全体に残りがあれば発掘は使える")
        void testMethod04() {
            putRow(TOTAL, TODAY, 40);
            putRow(INTERACTIVE, TODAY, 40);

            boolean acquired = budgetAt(NOON_UTC).tryAcquireForDiscovery();

            assertThat(acquired).isTrue();
            assertThat(requestsOf(TOTAL)).isEqualTo(41);
            assertThat(requestsOf(DISCOVERY)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("markExhausted()")
    class MarkExhausted {

        @Test
        @DisplayName("正常系：全体の行を 1 日の上限にし、以後の検索と発掘を止める")
        void testMethod01() {
            YouTubeSearchBudget budget = budgetAt(NOON_UTC);

            budget.markExhausted();

            assertThat(requestsOf(TOTAL)).isEqualTo(52);
            assertThatThrownBy(() -> budget.acquireForUser("alice")).isInstanceOf(SearchQuotaExceededException.class);
            assertThat(budget.tryAcquireForDiscovery()).isFalse();
        }

        @Test
        @DisplayName("正常系：上限より多い回数は下げない")
        void testMethod02() {
            putRow(TOTAL, TODAY, 60);

            budgetAt(NOON_UTC).markExhausted();

            assertThat(requestsOf(TOTAL)).isEqualTo(60);
        }

        @Test
        @DisplayName("正常系：前日の行は今日の日付にしてから上限にする")
        void testMethod03() {
            putRow(TOTAL, YESTERDAY, 5);

            budgetAt(NOON_UTC).markExhausted();

            assertThat(rows.get(TOTAL).getQuotaDate()).isEqualTo(TODAY);
            assertThat(requestsOf(TOTAL)).isEqualTo(52);
        }
    }

    @Nested
    @DisplayName("status()")
    class Status {

        @Test
        @DisplayName("正常系：対話とその人の残りを返し、回数は増やさない")
        void testMethod01() {
            putRow(TOTAL, TODAY, 8);
            putRow(INTERACTIVE, TODAY, 5);
            putRow(ALICE, TODAY, 3);

            SearchQuotaStatus status = budgetAt(NOON_UTC).status("alice");

            assertThat(status.interactiveRemaining()).isEqualTo(35);
            assertThat(status.userRemaining()).isEqualTo(7);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("正常系：全体の残りが少ないときは、対話とその人の残りも全体の残りまでにする")
        void testMethod02() {
            putRow(TOTAL, TODAY, 51);
            putRow(INTERACTIVE, TODAY, 30);

            SearchQuotaStatus status = budgetAt(NOON_UTC).status("alice");

            assertThat(status.interactiveRemaining()).isEqualTo(1);
            assertThat(status.userRemaining()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系：全体の回数が上限を超えていても、残りは負にせず 0 にする")
        void testMethod03() {
            // markExhausted() の後の 52 では全体の残りがちょうど 0 になり、負の丸めを確かめられないため 60 にする
            putRow(TOTAL, TODAY, 60);
            putRow(INTERACTIVE, TODAY, 0);
            putRow(ALICE, TODAY, 0);

            SearchQuotaStatus status = budgetAt(NOON_UTC).status("alice");

            assertThat(status.interactiveRemaining()).isZero();
            assertThat(status.userRemaining()).isZero();
        }

        @Test
        @DisplayName("正常系：前日の行の回数は数えない")
        void testMethod04() {
            putRow(TOTAL, YESTERDAY, 52);
            putRow(INTERACTIVE, YESTERDAY, 40);
            putRow(ALICE, YESTERDAY, 10);

            SearchQuotaStatus status = budgetAt(NOON_UTC).status("alice");

            assertThat(status.interactiveRemaining()).isEqualTo(40);
            assertThat(status.userRemaining()).isEqualTo(10);
        }

        @Test
        @DisplayName("正常系：resetsAt は次の太平洋時間の 0 時で、夏時間と冬時間で日本時間の時刻が変わる")
        void testMethod05() {
            // { 今の時刻, 期待する resetsAt }。夏時間の 0 時は 07:00Z、冬時間（2026-11-01 から）の 0 時は 08:00Z
            String[][] cases = {
                    {"2026-09-27T06:59:59Z", "2026-09-27T07:00:00Z"},
                    {"2026-09-27T07:00:00Z", "2026-09-28T07:00:00Z"},
                    {"2026-10-31T12:00:00Z", "2026-11-01T07:00:00Z"},
                    {"2026-11-01T12:00:00Z", "2026-11-02T08:00:00Z"},
                    {"2026-12-01T07:59:59Z", "2026-12-01T08:00:00Z"},
            };

            for (String[] c : cases) {
                assertThat(budgetAt(c[0]).status("alice").resetsAt())
                        .as("%s の次の区切り", c[0])
                        .isEqualTo(Instant.parse(c[1]));
            }
        }
    }

    @Nested
    @DisplayName("discoveryUsedToday()")
    class DiscoveryUsedToday {

        @Test
        @DisplayName("正常系：今日の発掘の回数を返し、前日の行は 0 とみなす")
        void testMethod01() {
            putRow(DISCOVERY, TODAY, 4);
            assertThat(budgetAt(NOON_UTC).discoveryUsedToday()).isEqualTo(4);

            putRow(DISCOVERY, YESTERDAY, 4);
            assertThat(budgetAt(NOON_UTC).discoveryUsedToday()).isZero();
        }
    }

    @Nested
    @DisplayName("acquireDetailForUser()")
    class AcquireDetailForUser {

        @Test
        @DisplayName("正常系：詳細の全体とその利用者の行を 1 ずつ増やし、検索の行は増やさない")
        void testMethod01() {
            budgetAt(NOON_UTC).acquireDetailForUser("alice");

            assertThat(rows).containsOnlyKeys(DETAIL_TOTAL, DETAIL_ALICE);
            assertThat(requestsOf(DETAIL_TOTAL)).isEqualTo(1);
            assertThat(requestsOf(DETAIL_ALICE)).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系：その利用者の詳細の行が上限なら例外にし、どの行も増やさない")
        void testMethod02() {
            putRow(DETAIL_ALICE, TODAY, 100);
            YouTubeSearchBudget budget = budgetAt(NOON_UTC);

            assertThatThrownBy(() -> budget.acquireDetailForUser("alice"))
                    .isInstanceOf(SearchQuotaExceededException.class)
                    .hasMessage("本日、動画の詳細を読み込める回数の上限に達しました（16〜17 時ごろに戻ります）。動画はこのまま再生できます");
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("異常系：詳細の全体の行が上限なら例外にし、どの行も増やさない")
        void testMethod03() {
            putRow(DETAIL_TOTAL, TODAY, 400);
            YouTubeSearchBudget budget = budgetAt(NOON_UTC);

            assertThatThrownBy(() -> budget.acquireDetailForUser("alice"))
                    .isInstanceOf(SearchQuotaExceededException.class);
            verify(repository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("acquireForAdmin()")
    class AcquireForAdmin {

        @Test
        @DisplayName("正常系：全体と対話の行を 1 ずつ増やし、利用者ごとの行は作らない")
        void testMethod01() {
            budgetAt(NOON_UTC).acquireForAdmin();

            assertThat(rows).containsOnlyKeys(TOTAL, INTERACTIVE);
            assertThat(requestsOf(TOTAL)).isEqualTo(1);
            assertThat(requestsOf(INTERACTIVE)).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系：対話の行が上限なら例外にし、どの行も増やさない")
        void testMethod02() {
            putRow(INTERACTIVE, TODAY, 40);
            YouTubeSearchBudget budget = budgetAt(NOON_UTC);

            assertThatThrownBy(budget::acquireForAdmin).isInstanceOf(SearchQuotaExceededException.class);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("異常系：全体の行が 1 日の上限なら例外にし、どの行も増やさない")
        void testMethod03() {
            putRow(TOTAL, TODAY, 52);
            YouTubeSearchBudget budget = budgetAt(NOON_UTC);

            assertThatThrownBy(budget::acquireForAdmin).isInstanceOf(SearchQuotaExceededException.class);
            verify(repository, never()).saveAndFlush(any());
        }
    }
}
