package com.example.monitor.scheduler;

import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.NotificationOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.Platform;
import com.example.monitor.platform.StreamPlatform;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.service.NotificationDispatcher;
import com.example.monitor.service.NotificationHistoryService;
import com.example.monitor.service.StreamRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LiveStreamPollingScheduler")
class LiveStreamPollingSchedulerTest {

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private StreamPlatformRegistry streamPlatformRegistry;

    @Mock
    private StreamPlatform streamPlatform;

    @Mock
    private NotificationDispatcher notificationDispatcher;

    @Mock
    private NotificationHistoryService notificationHistoryService;

    @Mock
    private StreamRecorder streamRecorder;

    @Mock
    private com.example.monitor.service.RecordingIntentResolver recordingIntentResolver;

    @Mock
    private com.example.monitor.service.OnlineVideoService onlineVideoService;

    /**
     * 利用者ごとの通知。モックのままにして、全体向けの通知の各テストの前提（利用者向けの通知先が無い）を変えない。
     * モックは詳細の入れ物を取り出さないため、詳細の取得回数の検証もこれまでどおり成り立つ。
     */
    @Mock
    private com.example.monitor.service.UserNotificationService userNotificationService;

    @Mock
    private com.example.monitor.service.PollingStatusTracker pollingStatusTracker;

    /** 判定失敗の見張り。モックのままにして、既存の各テストで Discord へ送らない。 */
    @Mock
    private com.example.monitor.service.DetectionFailureAlerter detectionFailureAlerter;

    @InjectMocks
    private LiveStreamPollingScheduler scheduler;

    /**
     * {@link #detects} で積んだチャンネルごとの検知結果。一括問い合わせの応答を組み立てるのに使う。
     */
    private final Map<String, LiveStreamDetection> detectionsByChannelId = new LinkedHashMap<>();

    @BeforeEach
    void stubRecordingIntent() {
        // 録画の希望は「チャンネル単位の設定」と「購読ごとの設定」の論理和で決まるようになったが、
        // ここでは購読が1件も無い状態（＝チャンネル単位の設定がそのまま結果になる）を模す。
        // こうすることで、購読を導入する前から続く各テストの前提と期待値を変えずに済む
        lenient().when(recordingIntentResolver.resolve(any(), any(), any())).thenAnswer(invocation -> {
            com.example.monitor.entity.MonitoredChannel channel = invocation.getArgument(0);
            return new com.example.monitor.service.RecordingIntentResolver.RecordingIntent(
                    channel.isRecordEnabled(),
                    channel.matchesFilter(invocation.getArgument(1), invocation.getArgument(2)));
        });
    }

    @BeforeEach
    void stubPlatformResolution() {
        // どのテストも「YouTube のチャンネルを扱う」前提。到達しないテストもあるため lenient にする
        lenient().when(streamPlatformRegistry.get(Platform.YOUTUBE)).thenReturn(streamPlatform);

        // スケジューラはプラットフォームごとにまとめて問い合わせる。StreamPlatform はモックなので
        // 既定実装（1件ずつ呼ぶループ）は動かない。ここで「与えられたIDのうち detects() で
        // 積まれているものだけを返す」という本物同等の応答を組み立てる
        lenient().when(streamPlatform.detectLiveStreams(anyList())).thenAnswer(invocation -> {
            List<String> channelIds = invocation.getArgument(0);
            Map<String, LiveStreamDetection> results = new LinkedHashMap<>();
            for (String channelId : channelIds) {
                LiveStreamDetection detection = detectionsByChannelId.get(channelId);
                // 積まれていないチャンネルは応答に含めない（実装が「判定できなかった」に倒す）
                if (detection != null) {
                    results.put(channelId, detection);
                }
            }
            return results;
        });
    }

    /**
     * 一括問い合わせがこのチャンネルについて返す検知結果を積む。
     *
     * @param channelId 対象のチャンネル識別子
     * @param detection そのチャンネルの検知結果
     */
    private void detects(String channelId, LiveStreamDetection detection) {
        detectionsByChannelId.put(channelId, detection);
    }

    /**
     * 視聴URLの組み立てはプラットフォーム側の責務で、検知結果に入って渡ってくる。
     * テストでは決め打ちの前置きを使う。
     */
    private static final String WATCH_URL_PREFIX = "https://www.youtube.com/watch?v=";

    private MonitoredChannel channel(long id, String youtubeChannelId, String lastNotifiedVideoId) {
        MonitoredChannel channel = new MonitoredChannel(youtubeChannelId, "テストチャンネル" + id);
        channel.setId(id);
        channel.setLastNotifiedVideoId(lastNotifiedVideoId);
        return channel;
    }

    @Nested
    @DisplayName("pollAllChannels()")
    class PollAllChannels {

        @Test
        @DisplayName("正常系：登録チャンネルが0件の場合は何もしない")
        void testMethod01() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());

            scheduler.pollAllChannels();

            verify(streamPlatform, never()).detectLiveStreams(anyList());
        }

        @Test
        @DisplayName("正常系：配信中でない場合は観測状態のみ更新し通知は行わない")
        void testMethod02() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.notLive());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).updateObservedLiveState(eq(1L), eq(false), isNull(), any(LocalDateTime.class));
            verify(streamPlatform, never()).fetchDetails(any(), anyString());
            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
        }

        @Test
        @DisplayName("正常系：配信中だが既に通知済みの動画IDの場合は通知しない")
        void testMethod03() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", "video001");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("video001", "配信タイトル", null, "https://www.youtube.com/watch?v=video001"));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).updateObservedLiveState(eq(1L), eq(true), eq("video001"), any());
            verify(streamPlatform, never()).fetchDetails(any(), anyString());
            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
        }

        @Test
        @DisplayName("正常系：新しい配信を検知した場合は通知・履歴記録・通知済みID更新を行う")
        void testMethod04() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", "oldVideo");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));

            LiveStreamDetails details = LiveStreamDetails.builder()
                    .videoId("newVideo").title("新配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));

            NotificationOutcome outcome = NotificationOutcome.success();
            when(notificationDispatcher.notifyLiveStreamStarted(details)).thenReturn(outcome);

            scheduler.pollAllChannels();

            verify(notificationHistoryService).recordAttempt(target, "newVideo", "新配信", outcome);
            verify(monitoredChannelRepository).updateLastNotifiedVideoId(1L, "newVideo");
        }

        @Test
        @DisplayName("正常系：通知に失敗した場合は通知済みIDを更新しない")
        void testMethod05() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));

            LiveStreamDetails details = LiveStreamDetails.builder()
                    .videoId("newVideo").title("新配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));

            NotificationOutcome outcome = NotificationOutcome.failure("送信エラー");
            when(notificationDispatcher.notifyLiveStreamStarted(details)).thenReturn(outcome);

            scheduler.pollAllChannels();

            verify(notificationHistoryService).recordAttempt(target, "newVideo", "新配信", outcome);
            verify(monitoredChannelRepository, never()).updateLastNotifiedVideoId(any(), any());
        }

        @Test
        @DisplayName("正常系：配信詳細情報が取得できない場合は通知処理を行わない")
        void testMethod06() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.empty());

            scheduler.pollAllChannels();

            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
            verify(notificationHistoryService, never()).recordAttempt(any(), any(), any(), any());
        }

        @Test
        @DisplayName("正常系：1チャンネルの後処理で例外が発生しても他のチャンネルの処理は継続する")
        void testMethod07() {
            MonitoredChannel broken = channel(1L, "UCbroken00", null);
            MonitoredChannel healthy = channel(2L, "UChealthy0", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(broken, healthy));
            detects("UCbroken00", LiveStreamDetection.notLive());
            detects("UChealthy0", LiveStreamDetection.notLive());
            doThrow(new RuntimeException("想定外のエラー"))
                    .when(monitoredChannelRepository)
                    .updateObservedLiveState(eq(1L), anyBoolean(), any(), any());

            scheduler.pollAllChannels();

            // 1件目で例外が出ても2件目は最後まで処理される
            verify(monitoredChannelRepository).updateObservedLiveState(eq(2L), eq(false), isNull(), any());
        }

        @Test
        @DisplayName("正常系：録画有効なチャンネルで新しい配信を検知した場合は録画を開始し録画済みIDを更新する")
        void testMethod08() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(true);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.empty());
            when(streamRecorder.startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信")).thenReturn(true);

            scheduler.pollAllChannels();

            verify(streamRecorder).startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信");
            verify(monitoredChannelRepository).updateLastRecordedVideoId(1L, "newVideo");
        }

        @Test
        @DisplayName("正常系：録画無効なチャンネルでは録画を開始しない")
        void testMethod09() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(false);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.empty());

            scheduler.pollAllChannels();

            verify(streamRecorder, never()).startRecording(any(), any(), any(), any());
            verify(monitoredChannelRepository, never()).updateLastRecordedVideoId(any(), any());
        }

        @Test
        @DisplayName("正常系：既に録画済みの動画IDの場合は再度録画を開始しない")
        void testMethod10() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(true);
            target.setLastRecordedVideoId("newVideo");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.empty());

            scheduler.pollAllChannels();

            verify(streamRecorder, never()).startRecording(any(), any(), any(), any());
        }

        @Test
        @DisplayName("正常系：録画の起動に失敗した場合は録画済みIDを更新しない")
        void testMethod11() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(true);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.empty());
            when(streamRecorder.startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信")).thenReturn(false);

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository, never()).updateLastRecordedVideoId(any(), any());
        }

        @Test
        @DisplayName("正常系：通知済みの配信でも録画がまだなら録画は行われる（通知と録画は独立している）")
        void testMethod12() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", "newVideo");
            target.setRecordEnabled(true);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamRecorder.startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信")).thenReturn(true);

            scheduler.pollAllChannels();

            verify(streamRecorder).startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信");
            // 通知は既に済んでいるため、詳細取得や通知処理は呼ばれない
            verify(streamPlatform, never()).fetchDetails(any(), any());
        }

        @Test
        @DisplayName("正常系：タイトルフィルターに一致する配信は録画される")
        void testMethod13() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(true);
            target.setRecordTitleKeywords("【ASMR】,【生配信】");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【ASMR】耳かき音フェチ", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.empty());
            when(streamRecorder.startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "【ASMR】耳かき音フェチ")).thenReturn(true);

            scheduler.pollAllChannels();

            verify(streamRecorder).startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "【ASMR】耳かき音フェチ");
            verify(monitoredChannelRepository).updateLastRecordedVideoId(1L, "newVideo");
        }

        @Test
        @DisplayName("正常系：タイトルフィルターに一致しない配信は録画されない")
        void testMethod14() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(true);
            target.setRecordTitleKeywords("【ASMR】,【生配信】");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【歌枠】カラオケ配信", null, "https://www.youtube.com/watch?v=newVideo"));

            scheduler.pollAllChannels();

            verify(streamRecorder, never()).startRecording(any(), any(), any(), any());
            verify(monitoredChannelRepository, never()).updateLastRecordedVideoId(any(), any());
        }

        @Test
        @DisplayName("正常系：タイトルフィルターに一致しない配信は通知されない")
        void testMethod24() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordTitleKeywords("【ASMR】,【生配信】");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【歌枠】カラオケ配信", null, "https://www.youtube.com/watch?v=newVideo"));

            scheduler.pollAllChannels();

            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
            verify(notificationHistoryService, never()).recordAttempt(any(), any(), any(), any());
            verify(monitoredChannelRepository, never()).updateLastNotifiedVideoId(any(), any());
        }

        @Test
        @DisplayName("正常系：フィルター不一致の配信では詳細取得のクォータを消費しない")
        void testMethod25() {
            // 対象外と分かった時点で打ち切るので、videos.list（クォータ1）まで到達しない
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordTitleKeywords("【ASMR】");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【歌枠】カラオケ配信", null, "https://www.youtube.com/watch?v=newVideo"));

            scheduler.pollAllChannels();

            verify(streamPlatform, never()).fetchDetails(any(), anyString());
        }

        @Test
        @DisplayName("正常系：タイトルフィルターに一致する配信は通知される")
        void testMethod26() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordTitleKeywords("【ASMR】,【生配信】");
            LiveStreamDetails details = LiveStreamDetails.builder()
                    .videoId("newVideo").title("【ASMR】耳かき音フェチ").build();
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【ASMR】耳かき音フェチ", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details))
                    .thenReturn(NotificationOutcome.success());

            scheduler.pollAllChannels();

            verify(notificationDispatcher).notifyLiveStreamStarted(details);
            verify(monitoredChannelRepository).updateLastNotifiedVideoId(1L, "newVideo");
        }

        @Test
        @DisplayName("正常系：フィルター未設定なら従来どおりすべての配信を通知する")
        void testMethod27() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            LiveStreamDetails details = LiveStreamDetails.builder()
                    .videoId("newVideo").title("【歌枠】カラオケ配信").build();
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【歌枠】カラオケ配信", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details))
                    .thenReturn(NotificationOutcome.success());

            scheduler.pollAllChannels();

            verify(notificationDispatcher).notifyLiveStreamStarted(details);
        }

        @Test
        @DisplayName("異常系：フィルター設定済みでタイトルが取得できない場合は通知を見送る")
        void testMethod28() {
            // タイトルが無いと「タグが入っている」と確認できないため通知しない。
            // ただし検知そのものの故障を示す可能性があるので、実装側では警告ログを残している
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordTitleKeywords("【ASMR】");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", null, null, "https://www.youtube.com/watch?v=newVideo"));

            scheduler.pollAllChannels();

            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
            verify(streamPlatform, never()).fetchDetails(any(), anyString());
        }

        @Test
        @DisplayName("正常系：タイトルに無くてもカテゴリがフィルターに一致すれば通知する")
        void testMethod29() {
            // Twitch は内容の申告がカテゴリ欄に寄るため、タイトルだけ見ると取りこぼす
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordTitleKeywords("ASMR");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", 
                    LiveStreamDetection.live("newVideo", "IM SLEEPING", "ASMR", "https://www.twitch.tv/foo"));

            LiveStreamDetails details = LiveStreamDetails.builder()
                    .videoId("newVideo").title("IM SLEEPING").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details))
                    .thenReturn(NotificationOutcome.success());

            scheduler.pollAllChannels();

            verify(notificationDispatcher).notifyLiveStreamStarted(details);
        }

        @Test
        @DisplayName("正常系：タイトルに無くてもカテゴリがフィルターに一致すれば録画する")
        void testMethod30() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", "newVideo");
            target.setRecordEnabled(true);
            target.setRecordTitleKeywords("ASMR");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", 
                    LiveStreamDetection.live("newVideo", "IM SLEEPING", "ASMR", "https://www.twitch.tv/foo"));
            when(streamRecorder.startRecording(target, "https://www.twitch.tv/foo", "newVideo", "IM SLEEPING"))
                    .thenReturn(true);

            scheduler.pollAllChannels();

            verify(streamRecorder).startRecording(target, "https://www.twitch.tv/foo", "newVideo", "IM SLEEPING");
        }

        @Test
        @DisplayName("正常系：タイトル・カテゴリのどちらもフィルターに一致しなければ通知しない")
        void testMethod31() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordTitleKeywords("ASMR");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live(
                    "newVideo", "Come be tired with me", "Always On", "https://www.twitch.tv/foo"));

            scheduler.pollAllChannels();

            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
            verify(streamPlatform, never()).fetchDetails(any(), anyString());
        }

        @Test
        @DisplayName("正常系：巡回中に重ねて呼ばれた場合は二重に実行しない")
        void testMethod15() {
            // 巡回中であることを示す印を立てた状態で呼ぶ（定期実行と手動実行の競合を模擬する）
            AtomicBoolean inProgress =
                    (AtomicBoolean) ReflectionTestUtils.getField(scheduler, "pollingInProgress");
            inProgress.set(true);

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository, never()).findAll();
        }

        @Test
        @DisplayName("異常系：判定できなかった場合は配信状態を書き換えず連続失敗だけを記録する")
        void testMethod18() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.failed());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).recordDetectionFailure(eq(1L), any(LocalDateTime.class));
            // 配信中かどうか分からないのに false を書くと「正常に調べて配信していなかった」と区別がつかなくなる
            verify(monitoredChannelRepository, never())
                    .updateObservedLiveState(any(), anyBoolean(), any(), any());
            verify(streamPlatform, never()).fetchDetails(any(), anyString());
            verify(streamRecorder, never()).startRecording(any(), any(), any(), any());
        }

        @Test
        @DisplayName("正常系：判定できた場合は連続失敗の記録を行わない")
        void testMethod19() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.notLive());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository, never()).recordDetectionFailure(any(), any());
        }

        @Test
        @DisplayName("正常系：通知に失敗すると失敗回数が加算される")
        void testMethod20() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));
            LiveStreamDetails details = LiveStreamDetails.builder().videoId("newVideo").title("新配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details))
                    .thenReturn(NotificationOutcome.failure("送信エラー"));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).incrementNotificationFailureCount(1L);
        }

        @Test
        @DisplayName("正常系：失敗回数が上限に達した配信へは再送信しない")
        void testMethod21() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setNotificationFailureCount(3); // MAX_NOTIFICATION_ATTEMPTS と同値
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));

            scheduler.pollAllChannels();

            // 詳細取得のクォータすら使わずに諦める
            verify(streamPlatform, never()).fetchDetails(any(), anyString());
            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
        }

        @Test
        @DisplayName("正常系：詳細情報を取得できなかった場合も失敗回数が加算される")
        void testMethod22() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.empty());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).incrementNotificationFailureCount(1L);
        }

        @Test
        @DisplayName("正常系：配信が終わると通知の失敗回数が0に戻る")
        void testMethod23() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setNotificationFailureCount(2);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.notLive());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).resetNotificationFailureCount(1L);
        }

        @Test
        @DisplayName("正常系：同じプラットフォームのチャンネルは1回にまとめて問い合わせる")
        void testMethod32() {
            // 1件ずつ問い合わせると Twitch の「1リクエストで100チャンネル」が活かせない
            MonitoredChannel first = channel(1L, "UCaaaaaaaa", null);
            MonitoredChannel second = channel(2L, "UCbbbbbbbb", null);
            MonitoredChannel third = channel(3L, "UCcccccccc", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(first, second, third));
            detects("UCaaaaaaaa", LiveStreamDetection.notLive());
            detects("UCbbbbbbbb", LiveStreamDetection.notLive());
            detects("UCcccccccc", LiveStreamDetection.notLive());

            scheduler.pollAllChannels();

            verify(streamPlatform, times(1))
                    .detectLiveStreams(List.of("UCaaaaaaaa", "UCbbbbbbbb", "UCcccccccc"));
            verify(monitoredChannelRepository).updateObservedLiveState(eq(1L), eq(false), isNull(), any());
            verify(monitoredChannelRepository).updateObservedLiveState(eq(2L), eq(false), isNull(), any());
            verify(monitoredChannelRepository).updateObservedLiveState(eq(3L), eq(false), isNull(), any());
        }

        @Test
        @DisplayName("正常系：プラットフォームが異なるチャンネルはそれぞれの実装へ振り分ける")
        void testMethod33() {
            StreamPlatform twitchPlatform = mock(StreamPlatform.class);
            when(streamPlatformRegistry.get(Platform.TWITCH)).thenReturn(twitchPlatform);
            when(twitchPlatform.detectLiveStreams(List.of("123456")))
                    .thenReturn(Map.of("123456", LiveStreamDetection.notLive()));

            MonitoredChannel youtube = channel(1L, "UCxxxxxxxx", null);
            MonitoredChannel twitch = channel(2L, "123456", null);
            twitch.setPlatform(Platform.TWITCH);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(youtube, twitch));
            detects("UCxxxxxxxx", LiveStreamDetection.notLive());

            scheduler.pollAllChannels();

            verify(streamPlatform).detectLiveStreams(List.of("UCxxxxxxxx"));
            verify(twitchPlatform).detectLiveStreams(List.of("123456"));
        }

        @Test
        @DisplayName("異常系：一括問い合わせの結果に含まれないチャンネルは判定失敗として扱う")
        void testMethod34() {
            // 応答に無いことを「配信していない」と解釈すると、検知の故障が平常運転に見える
            MonitoredChannel missing = channel(1L, "UCmissing0", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(missing));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).recordDetectionFailure(eq(1L), any(LocalDateTime.class));
            verify(monitoredChannelRepository, never())
                    .updateObservedLiveState(any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("異常系：一括問い合わせ自体が失敗した場合も全件を判定失敗として扱う")
        void testMethod35() {
            MonitoredChannel first = channel(1L, "UCaaaaaaaa", null);
            MonitoredChannel second = channel(2L, "UCbbbbbbbb", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(first, second));
            when(streamPlatform.detectLiveStreams(anyList()))
                    .thenThrow(new RuntimeException("通信エラー"));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).recordDetectionFailure(eq(1L), any(LocalDateTime.class));
            verify(monitoredChannelRepository).recordDetectionFailure(eq(2L), any(LocalDateTime.class));
            verify(monitoredChannelRepository, never())
                    .updateObservedLiveState(any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("正常系：別の配信を検知したら通知の失敗回数を数え直して通知を試みる")
        void testMethod36() {
            // 巡回間隔内での枠の差し替えや、アプリ停止中の切り替えでは NOT_LIVE を
            // 一度も挟まずに次の配信へ移る。ここで失敗回数を持ち越すと、新しい配信への
            // 通知が一度も試されないまま終わる
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setCurrentLiveVideoId("videoA");
            target.setNotificationFailureCount(3); // MAX_NOTIFICATION_ATTEMPTS と同値
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("videoB", "別の配信", null,
                    "https://www.youtube.com/watch?v=videoB"));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).resetNotificationFailureCount(1L);
            // DB を 0 に戻すだけでは足りない。このサイクルの上限判定も 0 として行われ、
            // 実際に詳細取得まで進むことを確かめる
            verify(streamPlatform).fetchDetails(any(), eq("videoB"));
        }

        @Test
        @DisplayName("正常系：同じ配信のままなら失敗回数を数え直さず再送もしない")
        void testMethod37() {
            // 直らない失敗（Webhook の設定ミスなど）で毎サイクル試行し続けるのを防ぐ、
            // という上限の意味がここで失われてはならない
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setCurrentLiveVideoId("videoA");
            target.setNotificationFailureCount(3);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("videoA", "同じ配信", null,
                    "https://www.youtube.com/watch?v=videoA"));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository, never()).resetNotificationFailureCount(anyLong());
            verify(streamPlatform, never()).fetchDetails(any(), anyString());
        }

        @Test
        @DisplayName("正常系：前の配信が分からない場合は失敗回数を数え直さない")
        void testMethod38() {
            // 分からないものを「別の配信だ」と断定すると、上限を設けた意味が消える
            // （「配信していない」と「判定できなかった」を区別するのと同じ考え方）
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setNotificationFailureCount(3);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("videoB", "新しい配信", null,
                    "https://www.youtube.com/watch?v=videoB"));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository, never()).resetNotificationFailureCount(anyLong());
            verify(streamPlatform, never()).fetchDetails(any(), anyString());
        }

        @Test
        @DisplayName("正常系：判定に失敗した回は配信状態にも失敗回数にも触れない")
        void testMethod39() {
            // 分からないものを false と書かない。既存の仕様が保たれていることを確かめる
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setCurrentLiveVideoId("videoA");
            target.setNotificationFailureCount(3);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.failed());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository, never()).resetNotificationFailureCount(anyLong());
            verify(monitoredChannelRepository, never())
                    .updateObservedLiveState(any(), anyBoolean(), any(), any());
            verify(monitoredChannelRepository).recordDetectionFailure(eq(1L), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("正常系：待機所を検知した場合はcurrentLiveVideoIdに予約枠の動画IDを書かない")
        void testMethod40() {
            // 配信中ではないため、updateObservedLiveState の videoId 引数には
            // 予約枠のIDではなくnullを渡す（「配信中でなければnull」というフィールドの前提を守る）
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.upcoming("upcomingVideo", "予定タイトル",
                    "https://www.youtube.com/watch?v=upcomingVideo", LocalDateTime.of(2026, 9, 24, 21, 0)));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository)
                    .updateObservedLiveState(eq(1L), eq(false), isNull(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("正常系：待機所を検知した場合は配信予定を記録する")
        void testMethod41() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            LocalDateTime scheduledStartTime = LocalDateTime.of(2026, 9, 24, 21, 0);
            detects("UCxxxxxxxx", LiveStreamDetection.upcoming("upcomingVideo", "予定タイトル",
                    "https://www.youtube.com/watch?v=upcomingVideo", scheduledStartTime));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository)
                    .updateUpcoming(1L, "upcomingVideo", "予定タイトル", scheduledStartTime);
            verify(monitoredChannelRepository, never()).clearUpcoming(anyLong());
        }

        @Test
        @DisplayName("正常系：配信中になったら配信予定の記録を消す（予定が現実になった）")
        void testMethod42() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setUpcomingVideoId("upcoming1");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx",
                    LiveStreamDetection.live("newVideo", "配信タイトル", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.empty());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).clearUpcoming(1L);
            verify(monitoredChannelRepository, never()).updateUpcoming(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("正常系：配信していないと判定した場合も配信予定の記録を消す（予定が消えた）")
        void testMethod43() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setUpcomingVideoId("upcoming1");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.notLive());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).clearUpcoming(1L);
            verify(monitoredChannelRepository, never()).updateUpcoming(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("異常系：判定できなかった場合は配信予定の記録にも触れない")
        void testMethod44() {
            // 分からないものを「予定が無い」と記録すると区別が付かなくなる
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.failed());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository, never()).updateUpcoming(anyLong(), any(), any(), any());
            verify(monitoredChannelRepository, never()).clearUpcoming(anyLong());
        }

        @Test
        @DisplayName("正常系：通知済みの配信でも利用者向けの通知は呼ぶ（全体向けの打ち切りより前）")
        void testMethod45() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", "video001");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("video001", "配信タイトル", null, WATCH_URL_PREFIX + "video001"));

            scheduler.pollAllChannels();

            verify(userNotificationService).notifySubscribers(eq(target), eq("video001"), any());
            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
        }

        @Test
        @DisplayName("正常系：全体向けのフィルターに一致しない配信でも利用者向けの通知は呼ぶ")
        void testMethod46() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordTitleKeywords("【ASMR】");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【歌枠】カラオケ配信", null, WATCH_URL_PREFIX + "newVideo"));

            scheduler.pollAllChannels();

            verify(userNotificationService).notifySubscribers(eq(target), eq("newVideo"), any());
            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
        }

        @Test
        @DisplayName("正常系：全体向けの失敗回数が上限に達していても利用者向けの通知は呼ぶ")
        void testMethod47() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setNotificationFailureCount(3); // MAX_NOTIFICATION_ATTEMPTS と同値
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));

            scheduler.pollAllChannels();

            verify(userNotificationService).notifySubscribers(eq(target), eq("newVideo"), any());
            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
        }

        @Test
        @DisplayName("正常系：配信していない・待機所・判定できなかったチャンネルでは利用者向けの通知を呼ばない")
        void testMethod48() {
            MonitoredChannel notLive = channel(1L, "UCnotlive0", null);
            MonitoredChannel upcoming = channel(2L, "UCupcoming", null);
            MonitoredChannel failed = channel(3L, "UCfailed00", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(notLive, upcoming, failed));
            detects("UCnotlive0", LiveStreamDetection.notLive());
            detects("UCupcoming", LiveStreamDetection.upcoming("upcomingVideo", "予定タイトル",
                    WATCH_URL_PREFIX + "upcomingVideo", LocalDateTime.of(2026, 9, 24, 21, 0)));
            // UCfailed00 は積まない（応答に含まれない＝判定できなかった）

            scheduler.pollAllChannels();

            verify(userNotificationService, never()).notifySubscribers(any(), any(), any());
        }

        @Test
        @DisplayName("正常系：利用者向けと全体向けの両方が詳細を使っても、詳細の取得は1回だけ")
        void testMethod49() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));
            LiveStreamDetails details = LiveStreamDetails.builder().videoId("newVideo").title("新配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details)).thenReturn(NotificationOutcome.success());
            // 送る相手が 2 人いる場合を模して、利用者向けの通知が詳細の入れ物を 2 回取り出す
            doAnswer(invocation -> {
                Supplier<Optional<LiveStreamDetails>> supplier = invocation.getArgument(2);
                supplier.get();
                supplier.get();
                return null;
            }).when(userNotificationService).notifySubscribers(any(), any(), any());

            scheduler.pollAllChannels();

            // YouTube では取得のたびにクォータを 1 使うので、同じ巡回で 2 回取らない
            verify(streamPlatform, times(1)).fetchDetails(any(), eq("newVideo"));
            verify(notificationDispatcher).notifyLiveStreamStarted(details);
        }

        @Test
        @DisplayName("正常系：全体向けがフィルターで打ち切られても、利用者向けが要れば詳細を1回だけ取る")
        void testMethod50() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordTitleKeywords("【ASMR】");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【歌枠】カラオケ配信", null, WATCH_URL_PREFIX + "newVideo"));
            LiveStreamDetails details = LiveStreamDetails.builder().videoId("newVideo").title("【歌枠】カラオケ配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            doAnswer(invocation -> {
                Supplier<Optional<LiveStreamDetails>> supplier = invocation.getArgument(2);
                supplier.get();
                return null;
            }).when(userNotificationService).notifySubscribers(any(), any(), any());

            scheduler.pollAllChannels();

            verify(streamPlatform, times(1)).fetchDetails(any(), eq("newVideo"));
            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
        }

        @Test
        @DisplayName("正常系：最後まで回った巡回は、判定できなかったチャンネルがあっても成功として記録する")
        void testMethod51() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.failed());

            scheduler.pollAllChannels();

            verify(pollingStatusTracker, times(1)).recordSuccess();
        }

        @Test
        @DisplayName("異常系：チャンネルの読み出しで例外が出た巡回は成功として記録せず、次の巡回は受け付ける")
        void testMethod52() {
            when(monitoredChannelRepository.findAll())
                    .thenThrow(new RuntimeException("DB に接続できません"))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> scheduler.pollAllChannels())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB に接続できません");
            verify(pollingStatusTracker, never()).recordSuccess();

            // 巡回中の印が finally で戻っていれば、次の巡回は実行される
            assertThat(scheduler.pollNow()).isTrue();
            verify(pollingStatusTracker, times(1)).recordSuccess();
        }

        @Test
        @DisplayName("正常系：監視を止めた起動（monitor.scheduling.enabled=false）では巡回しない")
        void testMethod53() {
            ReflectionTestUtils.setField(scheduler, "schedulingEnabled", false);

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository, never()).findAll();
            verify(streamPlatform, never()).detectLiveStreams(anyList());
            verify(pollingStatusTracker, never()).recordSuccess();
        }

        @Test
        @DisplayName("異常系：視聴先の保存で例外が出ても、録画と通知は続ける")
        void testMethod54() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(true);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));
            doThrow(new RuntimeException("視聴先の保存に失敗")).when(onlineVideoService).observe(any(), any());
            when(streamRecorder.startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信")).thenReturn(true);
            LiveStreamDetails details = LiveStreamDetails.builder().videoId("newVideo").title("新配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details)).thenReturn(NotificationOutcome.success());

            scheduler.pollAllChannels();

            verify(streamRecorder).startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信");
            verify(userNotificationService).notifySubscribers(eq(target), eq("newVideo"), any());
            verify(notificationDispatcher).notifyLiveStreamStarted(details);
            verify(monitoredChannelRepository).updateLastNotifiedVideoId(1L, "newVideo");
        }

        @Test
        @DisplayName("正常系：読み取れたアイコンが前と違えば記録する")
        void testMethod55() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setChannelIconUrl("https://yt3.ggpht.com/old");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.notLive().withChannelIcon("https://yt3.ggpht.com/new"));

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).updateChannelIconUrl(1L, "https://yt3.ggpht.com/new");
        }

        @Test
        @DisplayName("正常系：アイコンが前と同じか読み取れなかった場合は記録し直さない")
        void testMethod56() {
            MonitoredChannel same = channel(1L, "UCsame0000", null);
            same.setChannelIconUrl("https://yt3.ggpht.com/same");
            MonitoredChannel unreadable = channel(2L, "UCnoicon00", null);
            unreadable.setChannelIconUrl("https://yt3.ggpht.com/keep");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(same, unreadable));
            detects("UCsame0000", LiveStreamDetection.notLive().withChannelIcon("https://yt3.ggpht.com/same"));
            // 一時的に読み取れなかっただけの回（null）で前の値を消さない
            detects("UCnoicon00", LiveStreamDetection.notLive());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository, never()).updateChannelIconUrl(anyLong(), any());
        }

        @Test
        @DisplayName("正常系：チャンネル単位で録画を希望していなくても、購読者の希望があれば録画する")
        void testMethod57() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", "newVideo");
            target.setRecordEnabled(false);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));
            // @BeforeEach のスタブ（チャンネル単位の設定だけを見る）を、このテストだけ「購読者が希望し条件にも合う」に差し替える
            doReturn(new com.example.monitor.service.RecordingIntentResolver.RecordingIntent(true, true))
                    .when(recordingIntentResolver).resolve(any(), any(), any());
            when(streamRecorder.startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信")).thenReturn(true);

            scheduler.pollAllChannels();

            verify(streamRecorder).startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信");
            verify(monitoredChannelRepository).updateLastRecordedVideoId(1L, "newVideo");
        }

        @Test
        @DisplayName("異常系：録画の開始で例外が出ても、利用者向け・全体向けの通知は続ける")
        void testMethod58() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(true);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));
            when(streamRecorder.startRecording(target, WATCH_URL_PREFIX + "newVideo", "newVideo", "新配信"))
                    .thenThrow(new RuntimeException("録画履歴の登録に失敗"));
            LiveStreamDetails details = LiveStreamDetails.builder().videoId("newVideo").title("新配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details)).thenReturn(NotificationOutcome.success());

            scheduler.pollAllChannels();

            verify(userNotificationService).notifySubscribers(eq(target), eq("newVideo"), any());
            verify(notificationDispatcher).notifyLiveStreamStarted(details);
            verify(monitoredChannelRepository).updateLastNotifiedVideoId(1L, "newVideo");
            // 録画済みにしない（次の巡回で録画をもう一度試す）
            verify(monitoredChannelRepository, never()).updateLastRecordedVideoId(any(), any());
        }

        @Test
        @DisplayName("異常系：録画の希望の判定で例外が出ても、利用者向け・全体向けの通知は続ける")
        void testMethod59() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(true);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));
            doThrow(new RuntimeException("購読の読み出しに失敗"))
                    .when(recordingIntentResolver).resolve(any(), any(), any());
            LiveStreamDetails details = LiveStreamDetails.builder().videoId("newVideo").title("新配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details)).thenReturn(NotificationOutcome.success());

            scheduler.pollAllChannels();

            verify(streamRecorder, never()).startRecording(any(), any(), any(), any());
            verify(userNotificationService).notifySubscribers(eq(target), eq("newVideo"), any());
            verify(notificationDispatcher).notifyLiveStreamStarted(details);
        }

        @Test
        @DisplayName("異常系：通知履歴の保存で例外が出ても、送信に成功した配信は通知済みにする")
        void testMethod60() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));
            LiveStreamDetails details = LiveStreamDetails.builder().videoId("newVideo").title("新配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details)).thenReturn(NotificationOutcome.success());
            doThrow(new RuntimeException("履歴の保存に失敗"))
                    .when(notificationHistoryService).recordAttempt(any(), any(), any(), any());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).updateLastNotifiedVideoId(1L, "newVideo");
        }

        @Test
        @DisplayName("異常系：通知履歴の保存で例外が出ても、送信の失敗は失敗回数に数える")
        void testMethod61() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));
            LiveStreamDetails details = LiveStreamDetails.builder().videoId("newVideo").title("新配信").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(details));
            when(notificationDispatcher.notifyLiveStreamStarted(details))
                    .thenReturn(NotificationOutcome.failure("x".repeat(1000)));
            doThrow(new RuntimeException("履歴の保存に失敗"))
                    .when(notificationHistoryService).recordAttempt(any(), any(), any(), any());

            scheduler.pollAllChannels();

            // 数えないと再送の上限（MAX_NOTIFICATION_ATTEMPTS）が効かなくなる
            verify(monitoredChannelRepository).incrementNotificationFailureCount(1L);
            verify(monitoredChannelRepository, never()).updateLastNotifiedVideoId(any(), any());
        }

        @Test
        @DisplayName("異常系：APIが配信開始前（upcoming）と答えたら通知せず、失敗として数える")
        void testMethod62() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));
            LiveStreamDetails upcomingDetails = LiveStreamDetails.builder()
                    .videoId("newVideo").title("新配信").broadcastStatus("upcoming").build();
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.of(upcomingDetails));

            scheduler.pollAllChannels();

            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
            verify(monitoredChannelRepository).incrementNotificationFailureCount(1L);
            // 検知結果から組み立てた詳細で送り直さない（待機所を通知しない）
            verify(streamPlatform, never()).fallbackDetails(any(), any());
        }

        @Test
        @DisplayName("正常系：APIで詳細を取れなければ、検知結果から組み立てた詳細で通知する")
        void testMethod63() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            LiveStreamDetection detection = LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo");
            detects("UCxxxxxxxx", detection);
            when(streamPlatform.fetchDetails(any(), eq("newVideo"))).thenReturn(Optional.empty());
            LiveStreamDetails fallback = LiveStreamDetails.builder().videoId("newVideo").title("新配信").build();
            when(streamPlatform.fallbackDetails(target.getChannelName(), detection)).thenReturn(Optional.of(fallback));
            when(notificationDispatcher.notifyLiveStreamStarted(fallback)).thenReturn(NotificationOutcome.success());

            scheduler.pollAllChannels();

            verify(notificationDispatcher).notifyLiveStreamStarted(fallback);
            verify(monitoredChannelRepository).updateLastNotifiedVideoId(1L, "newVideo");
        }

        @Test
        @DisplayName("正常系：プラットフォームごとに判定結果を判定失敗の見張りへ渡す")
        void testMethod64() {
            StreamPlatform twitchPlatform = mock(StreamPlatform.class);
            when(streamPlatformRegistry.get(Platform.TWITCH)).thenReturn(twitchPlatform);
            when(twitchPlatform.detectLiveStreams(List.of("123456")))
                    .thenReturn(Map.of("123456", LiveStreamDetection.notLive()));
            MonitoredChannel youtube = channel(1L, "UCxxxxxxxx", null);
            MonitoredChannel twitch = channel(2L, "123456", null);
            twitch.setPlatform(Platform.TWITCH);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(youtube, twitch));
            detects("UCxxxxxxxx", LiveStreamDetection.notLive());

            scheduler.pollAllChannels();

            verify(detectionFailureAlerter).recordPlatformResult(eq(Platform.YOUTUBE), eq(List.of(youtube)), any());
            verify(detectionFailureAlerter).recordPlatformResult(eq(Platform.TWITCH), eq(List.of(twitch)), any());
        }

        @Test
        @DisplayName("異常系：判定失敗の見張りで例外が出ても、残りのプラットフォームを回して巡回を成功と記録する")
        void testMethod65() {
            StreamPlatform twitchPlatform = mock(StreamPlatform.class);
            when(streamPlatformRegistry.get(Platform.TWITCH)).thenReturn(twitchPlatform);
            when(twitchPlatform.detectLiveStreams(List.of("123456")))
                    .thenReturn(Map.of("123456", LiveStreamDetection.notLive()));
            MonitoredChannel youtube = channel(1L, "UCxxxxxxxx", null);
            MonitoredChannel twitch = channel(2L, "123456", null);
            twitch.setPlatform(Platform.TWITCH);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(youtube, twitch));
            detects("UCxxxxxxxx", LiveStreamDetection.notLive());
            doThrow(new RuntimeException("見張りの失敗"))
                    .when(detectionFailureAlerter).recordPlatformResult(eq(Platform.YOUTUBE), any(), any());

            scheduler.pollAllChannels();

            verify(monitoredChannelRepository).updateObservedLiveState(eq(2L), eq(false), isNull(), any());
            verify(detectionFailureAlerter).recordPlatformResult(eq(Platform.TWITCH), any(), any());
            verify(pollingStatusTracker).recordSuccess();
        }

        @Test
        @DisplayName("正常系：録画済みの配信でまだ条件に合えば、録画側へ「まだ配信中」と知らせ、録画は始め直さない")
        void testMethod66() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", "newVideo");
            target.setRecordEnabled(true);
            target.setLastRecordedVideoId("newVideo");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, WATCH_URL_PREFIX + "newVideo"));

            scheduler.pollAllChannels();

            // すぐ失敗した録画の録り直しは、この知らせを待って StreamRecorder が行う
            verify(streamRecorder).confirmStillLive("newVideo");
            verify(streamRecorder, never()).startRecording(any(), any(), any(), any());
            verify(monitoredChannelRepository, never()).updateLastRecordedVideoId(any(), any());
        }

        @Test
        @DisplayName("正常系：録画済みの配信でも条件から外れていれば、「まだ配信中」と知らせない")
        void testMethod67() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", "newVideo");
            target.setRecordEnabled(true);
            target.setRecordTitleKeywords("【ASMR】");
            target.setLastRecordedVideoId("newVideo");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【歌枠】カラオケ配信", null, WATCH_URL_PREFIX + "newVideo"));

            scheduler.pollAllChannels();

            // 条件から外れた配信は、合図が来ないまま待ちが切れて録り直さない
            verify(streamRecorder, never()).confirmStillLive(any());
            verify(streamRecorder, never()).startRecording(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("pollNow()")
    class PollNow {

        @Test
        @DisplayName("正常系：巡回を実行してtrueを返す")
        void testMethod01() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.notLive());

            boolean result = scheduler.pollNow();

            assertThat(result).isTrue();
            verify(streamPlatform).detectLiveStreams(List.of("UCxxxxxxxx"));
        }

        @Test
        @DisplayName("異常系：既に巡回中の場合は実行せずfalseを返す")
        void testMethod02() {
            AtomicBoolean inProgress =
                    (AtomicBoolean) ReflectionTestUtils.getField(scheduler, "pollingInProgress");
            inProgress.set(true);

            boolean result = scheduler.pollNow();

            assertThat(result).isFalse();
            verify(monitoredChannelRepository, never()).findAll();
        }

        @Test
        @DisplayName("正常系：巡回が終わると次の実行を受け付ける状態に戻る")
        void testMethod03() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());

            assertThat(scheduler.pollNow()).isTrue();
            assertThat(scheduler.pollNow()).isTrue();
        }
    }
}
