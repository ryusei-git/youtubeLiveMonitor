package com.example.monitor.service;

import com.example.monitor.dto.SubscribedChannelResponse;
import com.example.monitor.dto.UpcomingStreamResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.exception.RecordingNotFoundException;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserSubscriptionService")
class UserSubscriptionServiceTest {

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private CurrentAppUser currentAppUser;

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private RecordingRepository recordingRepository;

    @Mock
    private MonitoredChannelService monitoredChannelService;

    @Mock
    private AuditLogger auditLogger;

    @InjectMocks
    private UserSubscriptionService userSubscriptionService;

    /** ログイン中の利用者。 */
    private final AppUser viewer = new AppUser("viewer", "hash", AppUser.Role.USER);

    @BeforeEach
    void logIn() {
        when(currentAppUser.require()).thenReturn(viewer);
    }

    private static MonitoredChannel channel(long id) {
        MonitoredChannel channel = new MonitoredChannel("UC" + id, "チャンネル" + id);
        channel.setId(id);
        return channel;
    }

    private UserSubscription subscriptionTo(MonitoredChannel channel) {
        return UserSubscription.builder().user(viewer).channel(channel).build();
    }

    @Nested
    @DisplayName("listMySubscriptions()")
    class ListMySubscriptions {

        @Test
        @DisplayName("正常系：再生できる録画の件数がチャンネルごとに入る")
        void testMethod01() {
            // 件数をチャンネルごとに変え、別のチャンネルの件数が入る取り違えも見つけられるようにする
            when(monitoredChannelService.countPlayableRecordingsByChannel()).thenReturn(Map.of(1L, 3L, 2L, 5L));
            when(userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(viewer))
                    .thenReturn(List.of(subscriptionTo(channel(1L)), subscriptionTo(channel(2L))));

            List<SubscribedChannelResponse> result = userSubscriptionService.listMySubscriptions();

            assertThat(result)
                    .extracting(SubscribedChannelResponse::id, SubscribedChannelResponse::recordingCount)
                    .containsExactly(tuple(1L, 3L), tuple(2L, 5L));
        }

        @Test
        @DisplayName("正常系：件数の無い（録画の無い）チャンネルは0になる")
        void testMethod02() {
            // 数えた結果には録画のあるチャンネルしか載らない
            when(monitoredChannelService.countPlayableRecordingsByChannel()).thenReturn(Map.of(1L, 3L));
            when(userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(viewer))
                    .thenReturn(List.of(subscriptionTo(channel(1L)), subscriptionTo(channel(2L))));

            List<SubscribedChannelResponse> result = userSubscriptionService.listMySubscriptions();

            assertThat(result)
                    .extracting(SubscribedChannelResponse::id, SubscribedChannelResponse::recordingCount)
                    .containsExactly(tuple(1L, 3L), tuple(2L, 0L));
        }
    }

    @Nested
    @DisplayName("listMyUpcomingStreams()")
    class ListMyUpcomingStreams {

        @Test
        @DisplayName("正常系：購読しているチャンネルの予定だけが返り、購読していないチャンネルの予定は含まない")
        void testMethod01() {
            LocalDateTime now = LocalDateTime.now();
            MonitoredChannel subscribed = channel(1L);
            subscribed.setUpcomingVideoId("subscribedVideo");
            subscribed.setUpcomingScheduledStartTime(now.plusHours(2));
            MonitoredChannel notSubscribed = channel(2L);
            notSubscribed.setUpcomingVideoId("otherVideo");
            notSubscribed.setUpcomingScheduledStartTime(now.plusHours(1));
            when(userSubscriptionRepository.findByUserOrderBySubscribedAtDesc(viewer))
                    .thenReturn(List.of(subscriptionTo(subscribed)));
            // 管理者の配信予定と同じく全チャンネルから選ぶ作りになると、購読していないチャンネルの予定まで返る。
            // 全チャンネルを引く経路にも購読していないチャンネルの予定を置き、混ざらないことを確かめる
            // （今の作りはどちらも呼ばないので lenient にする）
            lenient().when(monitoredChannelService.findAll()).thenReturn(List.of(subscribed, notSubscribed));
            lenient().when(monitoredChannelRepository.findAll()).thenReturn(List.of(subscribed, notSubscribed));

            List<UpcomingStreamResponse> result = userSubscriptionService.listMyUpcomingStreams();

            assertThat(result)
                    .extracting(UpcomingStreamResponse::channelId, UpcomingStreamResponse::videoId)
                    .containsExactly(tuple(1L, "subscribedVideo"));
        }
    }

    @Nested
    @DisplayName("findMyRecording()")
    class FindMyRecording {

        @Test
        @DisplayName("正常系：購読しているチャンネルの録画を返す")
        void testMethod01() {
            MonitoredChannel subscribed = channel(1L);
            Recording recording = Recording.builder().id(100L).channel(subscribed).build();
            when(recordingRepository.findById(100L)).thenReturn(Optional.of(recording));
            when(userSubscriptionRepository.existsByUserAndChannel(viewer, subscribed)).thenReturn(true);

            assertThat(userSubscriptionService.findMyRecording(100L)).isSameAs(recording);
        }

        @Test
        @DisplayName("異常系：録画が無い場合はRecordingNotFoundExceptionを投げる")
        void testMethod02() {
            when(recordingRepository.findById(100L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userSubscriptionService.findMyRecording(100L))
                    .isInstanceOf(RecordingNotFoundException.class);
        }

        @Test
        @DisplayName("異常系：購読していないチャンネルの録画は、無い場合と同じRecordingNotFoundExceptionを投げる")
        void testMethod03() {
            // 権限不足として区別すると、ID を順に試すだけで購読外の録画がどれだけあるか分かってしまう
            MonitoredChannel notSubscribed = channel(2L);
            Recording recording = Recording.builder().id(100L).channel(notSubscribed).build();
            when(recordingRepository.findById(100L)).thenReturn(Optional.of(recording));
            when(userSubscriptionRepository.existsByUserAndChannel(viewer, notSubscribed)).thenReturn(false);

            assertThatThrownBy(() -> userSubscriptionService.findMyRecording(100L))
                    .isInstanceOf(RecordingNotFoundException.class);
        }

        @Test
        @DisplayName("異常系：チャンネルに紐づかない録画（管理者がURLを貼って取得したもの）はRecordingNotFoundExceptionを投げる")
        void testMethod04() {
            Recording recording = Recording.builder().id(100L).channel(null).build();
            when(recordingRepository.findById(100L)).thenReturn(Optional.of(recording));

            assertThatThrownBy(() -> userSubscriptionService.findMyRecording(100L))
                    .isInstanceOf(RecordingNotFoundException.class);
        }
    }
}
