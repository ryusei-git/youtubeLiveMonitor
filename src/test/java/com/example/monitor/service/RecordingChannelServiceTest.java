package com.example.monitor.service;

import com.example.monitor.dto.RecordingChannelResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingChannelService")
class RecordingChannelServiceTest {

    @Mock
    private RecordingRepository recordingRepository;

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @InjectMocks
    private RecordingChannelService service;

    private static final LocalDateTime OLDER = LocalDateTime.of(2026, 10, 1, 20, 0);
    private static final LocalDateTime NEWER = LocalDateTime.of(2026, 10, 5, 20, 0);

    private final AppUser user = new AppUser("user", "hashed-password", AppUser.Role.USER);
    private final MonitoredChannel alpha = channel(1L, "Alpha");
    private final MonitoredChannel beta = channel(2L, "beta");
    private final MonitoredChannel gamma = channel(3L, "Gamma");
    private final MonitoredChannel empty = channel(4L, "空のチャンネル");

    private static MonitoredChannel channel(Long id, String name) {
        MonitoredChannel channel = new MonitoredChannel("UC" + id, name);
        channel.setId(id);
        return channel;
    }

    private static Recording thumbnail(Long id, MonitoredChannel channel, String path) {
        return Recording.builder().id(id).channel(channel).thumbnailPath(path).build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(recordingRepository.summarizeByChannel(anyCollection())).thenReturn(List.of(
                new Object[]{1L, 3L, 5400L, OLDER},
                new Object[]{2L, 1L, 0L, NEWER},
                new Object[]{3L, 2L, 60L, OLDER}));
        lenient().when(recordingRepository.findLatestWithThumbnailByChannel(anyCollection())).thenReturn(List.of(
                // 開始時刻が同じ録画が 2 件返ったときは、主キーの大きい方を採る
                thumbnail(11L, alpha, "alpha/new.jpg"),
                thumbnail(10L, alpha, "alpha/old.jpg")));
        lenient().when(appUserRepository.findByUsername("user")).thenReturn(Optional.of(user));
        lenient().when(userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(user)).thenReturn(List.of(
                UserSubscription.builder().user(user).channel(beta).build()));
    }

    @Nested
    @DisplayName("list()")
    class ListChannels {

        @Test
        @DisplayName("正常系：最新の録画が新しい順に並べ、同じ時刻は名前順、録画の無いチャンネルは末尾に置く")
        void testMethod01() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(empty, gamma, beta, alpha));

            List<RecordingChannelResponse> result = service.list("user");

            assertThat(result).extracting(RecordingChannelResponse::channelName)
                    .containsExactly("beta", "Alpha", "Gamma", "空のチャンネル");
        }

        @Test
        @DisplayName("正常系：件数・合計・サムネイル・購読の有無を添え、録画の無いチャンネルは 0 件と null にする")
        void testMethod02() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(alpha, beta, empty));

            List<RecordingChannelResponse> result = service.list("user");

            RecordingChannelResponse first = result.stream().filter(r -> r.id() == 1L).findFirst().orElseThrow();
            assertThat(first.recordingCount()).isEqualTo(3);
            assertThat(first.totalDurationSeconds()).isEqualTo(5400);
            assertThat(first.latestThumbnailPath()).isEqualTo("alpha/new.jpg");
            assertThat(first.subscribed()).isFalse();
            assertThat(first.channelUrl()).isEqualTo("https://www.youtube.com/channel/UC1");

            RecordingChannelResponse second = result.stream().filter(r -> r.id() == 2L).findFirst().orElseThrow();
            assertThat(second.subscribed()).isTrue();
            assertThat(second.latestThumbnailPath()).isNull();

            RecordingChannelResponse none = result.stream().filter(r -> r.id() == 4L).findFirst().orElseThrow();
            assertThat(none.recordingCount()).isZero();
            assertThat(none.latestStartedAt()).isNull();
        }

        @Test
        @DisplayName("正常系：利用者が見つからなければ、どのチャンネルも購読していないものとして返す")
        void testMethod03() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(beta));
            when(appUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThat(service.list("ghost")).extracting(RecordingChannelResponse::subscribed).containsExactly(false);
        }
    }

    @Nested
    @DisplayName("get()")
    class GetChannel {

        @Test
        @DisplayName("正常系：一覧と同じ集計から 1 チャンネル分を返す")
        void testMethod01() {
            when(monitoredChannelRepository.findById(2L)).thenReturn(Optional.of(beta));

            RecordingChannelResponse result = service.get("user", 2L);

            assertThat(result.channelName()).isEqualTo("beta");
            assertThat(result.recordingCount()).isEqualTo(1);
            assertThat(result.latestStartedAt()).isEqualTo(NEWER);
            assertThat(result.subscribed()).isTrue();
        }

        @Test
        @DisplayName("異常系：チャンネルが無ければ ChannelNotFoundException を投げる")
        void testMethod02() {
            when(monitoredChannelRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.get("user", 99L)).isInstanceOf(ChannelNotFoundException.class);
        }
    }
}
