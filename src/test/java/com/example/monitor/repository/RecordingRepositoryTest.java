package com.example.monitor.repository;

import com.example.monitor.dto.RecordingGenreCountResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.entity.RecordingMark;
import com.example.monitor.entity.UserSubscription;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 録画の一覧検索の決まり（チャンネルは必ず外部結合する・印は検索した利用者の分だけを結合する・
 * 購読の限定はページングの前に効かせる・ジャンルの件数は同じ条件の一覧の件数と一致させる）は、
 * JPQL の結合条件の中にしか書かれていない。{@code RecordingHistoryServiceTest} はこのリポジトリをモックにしているので、
 * {@code LEFT JOIN} が内部結合に変わっても気付けない（内部結合になって URL 指定の録画が一覧から消えたことが実際にある）。
 * {@code @DataJpaTest} でインメモリ H2 に実際に流して確かめる。
 */
@DataJpaTest
@DisplayName("RecordingRepository")
class RecordingRepositoryTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);
    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 9, 27, 12, 0);

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private RecordingMarkRepository recordingMarkRepository;

    @Autowired
    private UserSubscriptionRepository userSubscriptionRepository;

    @Autowired
    private MonitoredChannelRepository monitoredChannelRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private TestEntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private AppUser persistUser(String username) {
        return appUserRepository.save(new AppUser(username, "hashed-password", AppUser.Role.USER));
    }

    private MonitoredChannel persistChannel(String youtubeChannelId, String channelName) {
        return monitoredChannelRepository.save(new MonitoredChannel(youtubeChannelId, channelName));
    }

    /** channel に null を渡すと、URL 指定で保存した録画（どのチャンネルにも紐づかない）になる。 */
    private Recording persistRecording(MonitoredChannel channel, String videoId, String videoTitle,
                                       String genre, RecordingStatus status) {
        return recordingRepository.save(Recording.builder()
                .channel(channel)
                .videoId(videoId)
                .videoTitle(videoTitle)
                .genre(genre)
                .filePath("test/" + videoId + ".mp4")
                .status(status)
                .build());
    }

    private void subscribe(AppUser user, MonitoredChannel channel) {
        userSubscriptionRepository.save(UserSubscription.builder().user(user).channel(channel).build());
    }

    private void mark(AppUser user, Recording recording, boolean watched, boolean favorite) {
        RecordingMark mark = new RecordingMark(user, recording);
        if (watched) {
            mark.setWatchedAt(Instant.parse("2026-09-27T03:00:00Z"));
        }
        mark.setFavorite(favorite);
        recordingMarkRepository.save(mark);
    }

    /** 状態・期間の条件は使わないので null に固定する。 */
    private Page<Recording> search(Long userId, boolean subscribedOnly, Long channelId, String keyword,
                                   String genre, Boolean watched, boolean favoriteOnly, boolean playableOnly) {
        return recordingRepository.search(userId, subscribedOnly, channelId, keyword, null, null, null,
                genre, watched, favoriteOnly, playableOnly, FIRST_PAGE);
    }

    /**
     * 総件数を、件数を数える問い合わせ（Spring Data が本体の JPQL から別に作る）を通して取る。
     * Spring Data は 1 ページ目の件数がページの大きさ未満だと件数の問い合わせを流さず、取れた件数をそのまま総件数にする。
     * FIRST_PAGE（20 件）では件数の問い合わせが一度も流れないので、1 件ずつのページで数える。
     */
    private long total(Long userId, boolean subscribedOnly, Long channelId, String keyword,
                       String genre, Boolean watched, boolean favoriteOnly, boolean playableOnly) {
        return recordingRepository.search(userId, subscribedOnly, channelId, keyword, null, null, null,
                genre, watched, favoriteOnly, playableOnly, PageRequest.of(0, 1)).getTotalElements();
    }

    private static List<String> videoIds(Page<Recording> page) {
        return page.getContent().stream().map(Recording::getVideoId).toList();
    }

    @Nested
    @DisplayName("search()")
    class Search {

        @Test
        @DisplayName("正常系：条件が無ければ、チャンネルに紐づかない録画（URL 指定の保存）も返す")
        void testMethod01() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            persistRecording(channelA, "va", "title", null, RecordingStatus.COMPLETED);
            persistRecording(null, "vurl", "title", null, RecordingStatus.COMPLETED);

            assertThat(videoIds(search(null, false, null, null, null, null, false, false)))
                    .containsExactlyInAnyOrder("va", "vurl");
            assertThat(total(null, false, null, null, null, null, false, false)).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系：キーワードは大文字小文字を区別せず、タイトルとチャンネル名のどちらに一致してもよい")
        void testMethod02() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            MonitoredChannel channelB = persistChannel("UCbbbbbbbbbbbbbbbbbbbbbb", "Singer B");
            persistRecording(channelA, "va", "Morning Stream", null, RecordingStatus.COMPLETED);
            persistRecording(channelB, "vb", "雑談", null, RecordingStatus.COMPLETED);
            persistRecording(null, "vurl", "Karaoke URL", null, RecordingStatus.COMPLETED);

            assertThat(videoIds(search(null, false, null, "stream", null, null, false, false)))
                    .containsExactlyInAnyOrder("va");
            assertThat(videoIds(search(null, false, null, "singer", null, null, false, false)))
                    .containsExactlyInAnyOrder("vb");
            assertThat(videoIds(search(null, false, null, "karaoke", null, null, false, false)))
                    .containsExactlyInAnyOrder("vurl");
        }

        @Test
        @DisplayName("正常系：チャンネルで絞り込むと、そのチャンネルの録画だけを返す")
        void testMethod03() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            MonitoredChannel channelB = persistChannel("UCbbbbbbbbbbbbbbbbbbbbbb", "Channel B");
            persistRecording(channelA, "va1", "title", null, RecordingStatus.COMPLETED);
            persistRecording(channelA, "va2", "title", null, RecordingStatus.COMPLETED);
            persistRecording(channelB, "vb", "title", null, RecordingStatus.COMPLETED);
            persistRecording(null, "vurl", "title", null, RecordingStatus.COMPLETED);

            assertThat(videoIds(search(null, false, channelA.getId(), null, null, null, false, false)))
                    .containsExactlyInAnyOrder("va1", "va2");
            assertThat(total(null, false, channelA.getId(), null, null, null, false, false)).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系：購読に限ると、購読しているチャンネルの録画だけを返し、チャンネルに紐づかない録画は返さない")
        void testMethod04() {
            AppUser alice = persistUser("alice");
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            MonitoredChannel channelB = persistChannel("UCbbbbbbbbbbbbbbbbbbbbbb", "Channel B");
            subscribe(alice, channelA);
            persistRecording(channelA, "va", "title", null, RecordingStatus.COMPLETED);
            persistRecording(channelB, "vb", "title", null, RecordingStatus.COMPLETED);
            persistRecording(null, "vurl", "title", null, RecordingStatus.COMPLETED);

            assertThat(videoIds(search(alice.getId(), true, null, null, null, null, false, false)))
                    .containsExactlyInAnyOrder("va");
            assertThat(total(alice.getId(), true, null, null, null, null, false, false)).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系：購読が 0 件の利用者が購読に限ると空になる")
        void testMethod05() {
            AppUser bob = persistUser("bob");
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            persistRecording(channelA, "va", "title", null, RecordingStatus.COMPLETED);

            assertThat(videoIds(search(bob.getId(), true, null, null, null, null, false, false))).isEmpty();
            assertThat(total(bob.getId(), true, null, null, null, null, false, false)).isZero();
        }

        @Test
        @DisplayName("正常系：視聴済み・未視聴の絞り込みは、検索した利用者の印だけを見る")
        void testMethod06() {
            AppUser alice = persistUser("alice");
            AppUser bob = persistUser("bob");
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            Recording v1 = persistRecording(channelA, "v1", "title", null, RecordingStatus.COMPLETED);
            Recording v2 = persistRecording(channelA, "v2", "title", null, RecordingStatus.COMPLETED);
            mark(alice, v1, true, false);
            mark(bob, v1, true, false);
            mark(bob, v2, true, false);

            assertThat(videoIds(search(alice.getId(), false, null, null, null, true, false, false)))
                    .containsExactlyInAnyOrder("v1");
            assertThat(videoIds(search(alice.getId(), false, null, null, null, false, false, false)))
                    .containsExactlyInAnyOrder("v2");
            assertThat(videoIds(search(bob.getId(), false, null, null, null, false, false, false))).isEmpty();
        }

        @Test
        @DisplayName("正常系：お気に入りの絞り込みは検索した利用者の印だけを見て、他人の印があっても行が増えない")
        void testMethod07() {
            AppUser alice = persistUser("alice");
            AppUser bob = persistUser("bob");
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            Recording v1 = persistRecording(channelA, "v1", "title", null, RecordingStatus.COMPLETED);
            Recording v2 = persistRecording(channelA, "v2", "title", null, RecordingStatus.COMPLETED);
            mark(alice, v1, false, true);
            mark(bob, v1, false, true);
            mark(bob, v2, false, true);

            assertThat(videoIds(search(alice.getId(), false, null, null, null, null, true, false)))
                    .containsExactlyInAnyOrder("v1");
            assertThat(total(alice.getId(), false, null, null, null, null, true, false)).isEqualTo(1);

            Page<Recording> all = search(alice.getId(), false, null, null, null, null, false, false);
            assertThat(videoIds(all)).containsExactlyInAnyOrder("v1", "v2");
            assertThat(all.getContent()).hasSize(2);
            assertThat(total(alice.getId(), false, null, null, null, null, false, false)).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系：再生できる録画に限ると、完了と途中までの録画だけを返す")
        void testMethod08() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            persistRecording(channelA, "vrec", "title", null, RecordingStatus.RECORDING);
            persistRecording(channelA, "vdone", "title", null, RecordingStatus.COMPLETED);
            persistRecording(channelA, "vpart", "title", null, RecordingStatus.PARTIAL);
            persistRecording(channelA, "vfail", "title", null, RecordingStatus.FAILED);

            assertThat(videoIds(search(null, false, null, null, null, null, false, true)))
                    .containsExactlyInAnyOrder("vdone", "vpart");
            assertThat(total(null, false, null, null, null, null, false, true)).isEqualTo(2);
            assertThat(videoIds(search(null, false, null, null, null, null, false, false)))
                    .containsExactlyInAnyOrder("vrec", "vdone", "vpart", "vfail");
        }

        @Test
        @DisplayName("正常系：返す録画はチャンネルを読み込み済みにしている")
        void testMethod09() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            persistRecording(channelA, "va", "title", null, RecordingStatus.COMPLETED);
            persistRecording(null, "vurl", "title", null, RecordingStatus.COMPLETED);
            flushAndClear();

            Map<String, Recording> byVideoId = search(null, false, null, null, null, null, false, false)
                    .getContent().stream()
                    .collect(Collectors.toMap(Recording::getVideoId, r -> r));

            assertThat(byVideoId).containsOnlyKeys("va", "vurl");
            assertThat(Hibernate.isInitialized(byVideoId.get("va").getChannel())).isTrue();
            assertThat(byVideoId.get("vurl").getChannel()).isNull();
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("正常系：チャンネルを読み込み済みで返す")
        void testMethod01() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            Recording va = persistRecording(channelA, "va", "title", null, RecordingStatus.COMPLETED);
            flushAndClear();

            Recording found = recordingRepository.findById(va.getId()).orElseThrow();

            assertThat(Hibernate.isInitialized(found.getChannel())).isTrue();
            assertThat(found.getChannel().getChannelName()).isEqualTo("Channel A");
        }
    }

    @Nested
    @DisplayName("countByGenre()")
    class CountByGenre {

        @Test
        @DisplayName("正常系：ジャンルの無い録画を数えず、件数の多い順に並べる")
        void testMethod01() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            persistRecording(channelA, "g1", "title", "歌枠", RecordingStatus.COMPLETED);
            persistRecording(channelA, "g2", "title", "歌枠", RecordingStatus.COMPLETED);
            persistRecording(channelA, "g3", "title", "雑談", RecordingStatus.COMPLETED);
            persistRecording(channelA, "g4", "title", null, RecordingStatus.COMPLETED);

            assertThat(recordingRepository.countByGenre(null, false)).containsExactly(
                    new RecordingGenreCountResponse("歌枠", 2),
                    new RecordingGenreCountResponse("雑談", 1));
        }

        @Test
        @DisplayName("正常系：各ジャンルの件数は、同じ条件でそのジャンルを検索したときの総件数と一致する")
        void testMethod02() {
            AppUser alice = persistUser("alice");
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            MonitoredChannel channelB = persistChannel("UCbbbbbbbbbbbbbbbbbbbbbb", "Channel B");
            subscribe(alice, channelA);
            persistRecording(channelA, "s1", "title", "歌枠", RecordingStatus.COMPLETED);
            persistRecording(channelA, "s2", "title", "歌枠", RecordingStatus.FAILED);
            persistRecording(channelB, "s3", "title", "歌枠", RecordingStatus.PARTIAL);
            persistRecording(null, "s4", "title", "歌枠", RecordingStatus.COMPLETED);
            persistRecording(channelA, "z1", "title", "雑談", RecordingStatus.RECORDING);
            persistRecording(channelB, "z2", "title", "雑談", RecordingStatus.COMPLETED);

            List<RecordingGenreCountResponse> allGenres = recordingRepository.countByGenre(null, false);
            List<RecordingGenreCountResponse> allPlayable = recordingRepository.countByGenre(null, true);
            List<RecordingGenreCountResponse> aliceGenres = recordingRepository.countByGenre(alice.getId(), false);
            List<RecordingGenreCountResponse> alicePlayable = recordingRepository.countByGenre(alice.getId(), true);

            assertThat(allGenres).containsExactly(
                    new RecordingGenreCountResponse("歌枠", 4),
                    new RecordingGenreCountResponse("雑談", 2));
            assertThat(allPlayable).containsExactly(
                    new RecordingGenreCountResponse("歌枠", 3),
                    new RecordingGenreCountResponse("雑談", 1));
            assertThat(aliceGenres).containsExactly(
                    new RecordingGenreCountResponse("歌枠", 2),
                    new RecordingGenreCountResponse("雑談", 1));
            assertThat(alicePlayable).containsExactly(
                    new RecordingGenreCountResponse("歌枠", 1));

            assertCountsMatchSearch(null, false, allGenres);
            assertCountsMatchSearch(null, true, allPlayable);
            assertCountsMatchSearch(alice.getId(), false, aliceGenres);
            assertCountsMatchSearch(alice.getId(), true, alicePlayable);
        }

        /** 各ジャンルの件数が、同じ条件でそのジャンルを検索したときの総件数（件数の問い合わせ）と一致することを確かめる。 */
        private void assertCountsMatchSearch(Long subscriberId, boolean playableOnly,
                                             List<RecordingGenreCountResponse> counts) {
            for (RecordingGenreCountResponse g : counts) {
                assertThat(g.count())
                        .as("genre=%s, subscriberId=%s, playableOnly=%s", g.genre(), subscriberId, playableOnly)
                        .isEqualTo(total(subscriberId, subscriberId != null, null, null, g.genre(), null, false,
                                playableOnly));
            }
        }
    }

    @Nested
    @DisplayName("markCompleted()")
    class MarkCompleted {

        @Test
        @DisplayName("正常系：状態を完了にし、ファイルサイズと完了時刻を書き、タイトルとジャンルには触れない")
        void testMethod01() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            Recording recording = persistRecording(channelA, "v1", "title", "歌枠", RecordingStatus.RECORDING);

            int updated = recordingRepository.markCompleted(recording.getId(), 1234L, COMPLETED_AT);

            flushAndClear();
            Recording reloaded = recordingRepository.findById(recording.getId()).orElseThrow();
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getStatus()).isEqualTo(RecordingStatus.COMPLETED);
            assertThat(reloaded.getFileSizeBytes()).isEqualTo(1234L);
            assertThat(reloaded.getCompletedAt()).isEqualTo(COMPLETED_AT);
            assertThat(reloaded.getVideoTitle()).isEqualTo("title");
            assertThat(reloaded.getGenre()).isEqualTo("歌枠");
        }

        @Test
        @DisplayName("異常系：対象の行が無ければ 0 を返す")
        void testMethod02() {
            int updated = recordingRepository.markCompleted(Long.MAX_VALUE, 1L, COMPLETED_AT);

            assertThat(updated).isZero();
        }
    }

    @Nested
    @DisplayName("markPartial()")
    class MarkPartial {

        @Test
        @DisplayName("正常系：状態を途中までにし、ファイルサイズと完了時刻を書く")
        void testMethod01() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            Recording recording = persistRecording(channelA, "v1", "title", "歌枠", RecordingStatus.RECORDING);

            int updated = recordingRepository.markPartial(recording.getId(), 567L, COMPLETED_AT);

            flushAndClear();
            Recording reloaded = recordingRepository.findById(recording.getId()).orElseThrow();
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getStatus()).isEqualTo(RecordingStatus.PARTIAL);
            assertThat(reloaded.getFileSizeBytes()).isEqualTo(567L);
            assertThat(reloaded.getCompletedAt()).isEqualTo(COMPLETED_AT);
        }
    }

    @Nested
    @DisplayName("markFailed()")
    class MarkFailed {

        @Test
        @DisplayName("正常系：状態を失敗にして完了時刻を書き、ファイルサイズは書かない")
        void testMethod01() {
            MonitoredChannel channelA = persistChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Channel A");
            Recording recording = persistRecording(channelA, "v1", "title", "歌枠", RecordingStatus.RECORDING);

            int updated = recordingRepository.markFailed(recording.getId(), COMPLETED_AT);

            flushAndClear();
            Recording reloaded = recordingRepository.findById(recording.getId()).orElseThrow();
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getStatus()).isEqualTo(RecordingStatus.FAILED);
            assertThat(reloaded.getCompletedAt()).isEqualTo(COMPLETED_AT);
            assertThat(reloaded.getFileSizeBytes()).isNull();
        }
    }
}
