package com.example.monitor.service;

import com.example.monitor.dto.SubscribedChannelResponse;
import com.example.monitor.dto.UpcomingStreamResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.platform.Platform;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserSubscriptionService")
class UserSubscriptionServiceTest {

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @Mock
    private CurrentAppUser currentAppUser;

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

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

    // ここから下の購読の操作は、リポジトリのスタブの引数に viewer を入れる。サービスが別の利用者で引くと、
    // 厳密スタブが PotentialStubbingProblem で落とすので、「ログイン中の利用者の購読だけを引く」ことを確かめられる

    @Nested
    @DisplayName("subscribe()")
    class Subscribe {

        @Test
        @DisplayName("異常系：購読が上限の50件に達していれば、チャンネルを登録する前に断る")
        void testMethod01() {
            when(userSubscriptionRepository.countByUser(viewer)).thenReturn(50L);

            assertThatThrownBy(() -> userSubscriptionService.subscribe(Platform.YOUTUBE, "UC1", "チャンネル1", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("購読できるチャンネルは50件までです。不要なチャンネルを解除してから追加してください。");
            // 上限の判定が findOrRegister より前にあること（後ろだと未登録チャンネルの行だけが増え、巡回対象が伸びる）
            verifyNoInteractions(monitoredChannelService);
            verify(userSubscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系：49件ならチャンネルを登録し、ログイン中の利用者の購読として保存する")
        void testMethod02() {
            MonitoredChannel target = channel(1L);
            when(userSubscriptionRepository.countByUser(viewer)).thenReturn(49L);
            when(monitoredChannelService.findOrRegister(Platform.YOUTUBE, "UC1", "チャンネル1")).thenReturn(target);
            when(userSubscriptionRepository.save(any(UserSubscription.class))).thenAnswer(call -> call.getArgument(0));

            SubscribedChannelResponse result = userSubscriptionService.subscribe(Platform.YOUTUBE, "UC1", "チャンネル1", true);

            assertThat(result.id()).isEqualTo(1L);
            assertThat(result.recordEnabled()).isTrue();
            ArgumentCaptor<UserSubscription> captor = ArgumentCaptor.forClass(UserSubscription.class);
            verify(userSubscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getUser()).isSameAs(viewer);
            assertThat(captor.getValue().getChannel()).isSameAs(target);
        }

        @Test
        @DisplayName("異常系：既に自分が購読していればChannelAlreadyRegisteredExceptionで、保存しない")
        void testMethod03() {
            MonitoredChannel target = channel(1L);
            when(userSubscriptionRepository.countByUser(viewer)).thenReturn(49L);
            when(monitoredChannelService.findOrRegister(Platform.YOUTUBE, "UC1", "チャンネル1")).thenReturn(target);
            when(userSubscriptionRepository.existsByUserAndChannel(viewer, target)).thenReturn(true);

            assertThatThrownBy(() -> userSubscriptionService.subscribe(Platform.YOUTUBE, "UC1", "チャンネル1", true))
                    .isInstanceOf(ChannelAlreadyRegisteredException.class);
            verify(userSubscriptionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateRecordSetting()")
    class UpdateRecordSetting {

        @Test
        @DisplayName("正常系：自分の購読の録画の希望とキーワードを変える")
        void testMethod01() {
            MonitoredChannel target = channel(1L);
            UserSubscription subscription = subscriptionTo(target);
            when(monitoredChannelRepository.findById(1L)).thenReturn(Optional.of(target));
            when(userSubscriptionRepository.findByUserAndChannel(viewer, target)).thenReturn(Optional.of(subscription));
            when(userSubscriptionRepository.save(any(UserSubscription.class))).thenAnswer(call -> call.getArgument(0));

            Optional<SubscribedChannelResponse> result = userSubscriptionService.updateRecordSetting(1L, true, "  歌枠  ");

            assertThat(result).isPresent();
            assertThat(result.get().recordEnabled()).isTrue();
            assertThat(result.get().recordTitleKeywords()).isEqualTo("歌枠");
        }

        @Test
        @DisplayName("異常系：自分が購読していないチャンネルなら空を返し、保存も記録もしない")
        void testMethod02() {
            MonitoredChannel target = channel(1L);
            when(monitoredChannelRepository.findById(1L)).thenReturn(Optional.of(target));
            when(userSubscriptionRepository.findByUserAndChannel(viewer, target)).thenReturn(Optional.empty());

            Optional<SubscribedChannelResponse> result = userSubscriptionService.updateRecordSetting(1L, true, "歌枠");

            assertThat(result).isEmpty();
            verify(userSubscriptionRepository, never()).save(any());
            verifyNoInteractions(auditLogger);
        }
    }

    @Nested
    @DisplayName("updateNotifySetting()")
    class UpdateNotifySetting {

        @Test
        @DisplayName("正常系：自分の購読の通知の希望を変える")
        void testMethod01() {
            MonitoredChannel target = channel(1L);
            UserSubscription subscription = subscriptionTo(target);
            when(monitoredChannelRepository.findById(1L)).thenReturn(Optional.of(target));
            when(userSubscriptionRepository.findByUserAndChannel(viewer, target)).thenReturn(Optional.of(subscription));
            when(userSubscriptionRepository.save(any(UserSubscription.class))).thenAnswer(call -> call.getArgument(0));

            Optional<SubscribedChannelResponse> result = userSubscriptionService.updateNotifySetting(1L, false);

            assertThat(result).isPresent();
            assertThat(result.get().notifyEnabled()).isFalse();
        }

        @Test
        @DisplayName("異常系：自分が購読していないチャンネルなら空を返し、保存も記録もしない")
        void testMethod02() {
            MonitoredChannel target = channel(1L);
            when(monitoredChannelRepository.findById(1L)).thenReturn(Optional.of(target));
            when(userSubscriptionRepository.findByUserAndChannel(viewer, target)).thenReturn(Optional.empty());

            Optional<SubscribedChannelResponse> result = userSubscriptionService.updateNotifySetting(1L, false);

            assertThat(result).isEmpty();
            verify(userSubscriptionRepository, never()).save(any());
            verifyNoInteractions(auditLogger);
        }
    }

    @Nested
    @DisplayName("unsubscribe()")
    class Unsubscribe {

        @Test
        @DisplayName("正常系：自分の購読1行だけを消してtrueを返し、記録する")
        void testMethod01() {
            MonitoredChannel target = channel(1L);
            when(monitoredChannelRepository.findById(1L)).thenReturn(Optional.of(target));
            when(userSubscriptionRepository.deleteByUserAndChannel(viewer, target)).thenReturn(1);

            assertThat(userSubscriptionService.unsubscribe(1L)).isTrue();
            verify(auditLogger).record(eq(AuditAction.CHANNEL_UNSUBSCRIBE), eq(AuditOutcome.SUCCESS), any(),
                    eq("viewer"), any(), eq("CHANNEL"), eq("1"), any());
        }

        @Test
        @DisplayName("異常系：購読していなければfalseで、記録しない")
        void testMethod02() {
            MonitoredChannel target = channel(1L);
            when(monitoredChannelRepository.findById(1L)).thenReturn(Optional.of(target));
            when(userSubscriptionRepository.deleteByUserAndChannel(viewer, target)).thenReturn(0);

            assertThat(userSubscriptionService.unsubscribe(1L)).isFalse();
            verifyNoInteractions(auditLogger);
        }

        @Test
        @DisplayName("異常系：チャンネルが無ければfalseで、削除を試みない")
        void testMethod03() {
            when(monitoredChannelRepository.findById(99L)).thenReturn(Optional.empty());

            assertThat(userSubscriptionService.unsubscribe(99L)).isFalse();
            verify(userSubscriptionRepository, never()).deleteByUserAndChannel(any(), any());
        }
    }
}
