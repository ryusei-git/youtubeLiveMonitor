package com.example.monitor.service;

import com.example.monitor.controller.OnlineVideoController;
import com.example.monitor.dto.*;
import com.example.monitor.entity.*;
import com.example.monitor.platform.Platform;
import com.example.monitor.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({OnlineVideoService.class, VideoCollectionTracker.class, OnlineVideoController.class})
class OnlineVideoServiceTest {
    @Autowired OnlineVideoService service;
    @Autowired OnlineVideoRepository videos;
    @Autowired VideoThumbnailRepository thumbnails;
    @Autowired VideoCollectionTracker tracker;
    @Autowired OnlineVideoController controller;
    @Autowired TestEntityManager em;
    @MockBean UptimeTracker uptime;
    MonitoredChannel channel;
    final String videoId = "abcdefghijk";

    @BeforeEach
    void setup() {
        when(uptime.getStartedAt()).thenReturn(LocalDateTime.now().minusMinutes(5));
        channel = em.persistAndFlush(new MonitoredChannel("UCabcdefghijklmnopqrstuv", "チャンネルA"));
    }

    OnlineVideoCandidate candidate(Instant published) {
        return new OnlineVideoCandidate("YOUTUBE_" + videoId, "新着動画", "https://www.youtube.com/watch?v=" + videoId,
                "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg", published);
    }

    @Nested
    class Capture {
        @Test @DisplayName("正常系：稼働前の動画を取り込まず新着は繰り返し取得しても一件だけ保存する")
        void testMethod01() {
            service.capture(channel, candidate(Instant.now().minusSeconds(3600)));
            assertThat(videos.count()).isZero();
            var recent = candidate(Instant.now().plusSeconds(1));
            service.capture(channel, recent); service.capture(channel, recent);
            assertThat(videos.count()).isEqualTo(1);
        }

        @Test @DisplayName("正常系：初回収集の境界を保存し再起動直前に投稿された動画を再開後に拾う")
        void testMethod02() {
            var boundary = tracker.collectingSince(channel);
            when(uptime.getStartedAt()).thenReturn(LocalDateTime.now().plusHours(1));
            service.capture(channel, candidate(boundary.plusSeconds(1)));
            assertThat(videos.count()).isEqualTo(1);
            assertThat(tracker.collectingSince(channel)).isEqualTo(boundary);
        }
    }

    @Nested
    class Observe {
        @Test @DisplayName("正常系：通知と録画の設定に関係なく保存し判定失敗では配信終了にしない")
        void testMethod01() {
            channel.setRecordEnabled(false); channel.setRecordTitleKeywords("一致しない条件");
            service.observe(channel, LiveStreamDetection.live(videoId, "配信", null, "https://www.youtube.com/watch?v=" + videoId));
            service.observe(channel, LiveStreamDetection.failed());
            assertThat(videos.findById("YOUTUBE_" + videoId).orElseThrow().isLive()).isTrue();
            service.observe(channel, LiveStreamDetection.notLive());
            assertThat(videos.findById("YOUTUBE_" + videoId).orElseThrow().isLive()).isFalse();
        }

        @Test @DisplayName("正常系：TwitchのVODは配信IDから作らずAPIで取得したURLを保持する")
        void testMethod02() {
            channel.setPlatform(Platform.TWITCH);
            var live = LiveStreamDetection.live("100", "配信", null, "https://www.twitch.tv/example");
            service.observe(channel, live);
            service.capture(channel, new OnlineVideoCandidate("TWITCH_stream_100", "配信", "https://www.twitch.tv/videos/999", "https://static-cdn.jtvnw.net/a.jpg", Instant.now()));
            service.observe(channel, live);
            var video = videos.findById("TWITCH_stream_100").orElseThrow();
            assertThat(video.getWatchUrl()).isEqualTo("https://www.twitch.tv/videos/999");
            assertThat(videos.count()).isEqualTo(1);
        }

        @Test @DisplayName("正常系：再起動直後の未確認データを配信中と表示しない")
        void testMethod03() {
            service.observe(channel, LiveStreamDetection.live(videoId, "配信", null, "https://www.youtube.com/watch?v=" + videoId));
            when(uptime.getStartedAt()).thenReturn(LocalDateTime.now().plusMinutes(1));
            assertThat(service.response(videos.findById("YOUTUBE_" + videoId).orElseThrow()).state()).isEqualTo("UNKNOWN");
        }
    }

    @Nested
    class Access {
        @Test @DisplayName("正常系：一覧と画像は購読中のチャンネルに限定し管理者は全件閲覧できる")
        void testMethod01() {
            service.capture(channel, candidate(Instant.now().plusSeconds(1)));
            var user = em.persistAndFlush(new AppUser("viewer", "hash", AppUser.Role.USER));
            var stranger = new UsernamePasswordAuthenticationToken("other", "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
            assertThat(controller.list(stranger, 0, 24, "", null, false, null).totalElements()).isZero();
            assertThatThrownBy(() -> controller.thumbnail("YOUTUBE_" + videoId, stranger)).isInstanceOf(ResponseStatusException.class);
            var subscription = new UserSubscription(); subscription.setUser(user); subscription.setChannel(channel); em.persistAndFlush(subscription);
            var auth = new UsernamePasswordAuthenticationToken("viewer", "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
            assertThat(controller.list(auth, 0, 24, "", null, false, null).totalElements()).isEqualTo(1);
            var admin = new UsernamePasswordAuthenticationToken("admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            assertThat(controller.list(admin, 0, 24, "", null, false, null).totalElements()).isEqualTo(1);
            assertThatThrownBy(() -> controller.list(auth, -1, 24, "", null, false, null)).isInstanceOf(ResponseStatusException.class);
        }

        @Test @DisplayName("正常系：チャンネル削除では視聴メタデータと保存サムネイルも連鎖削除する")
        void testMethod02() {
            service.capture(channel, candidate(Instant.now().plusSeconds(1)));
            var video = videos.findById("YOUTUBE_" + videoId).orElseThrow();
            var thumbnail = new VideoThumbnail(); thumbnail.setVideo(video); thumbnail.setId(video.getId());
            thumbnail.setContentType("image/jpeg"); thumbnail.setContent(new byte[]{1, 2, 3}); thumbnails.saveAndFlush(thumbnail);
            em.remove(channel); em.flush(); em.clear();
            assertThat(videos.count()).isZero(); assertThat(thumbnails.count()).isZero();
        }
    }
}
