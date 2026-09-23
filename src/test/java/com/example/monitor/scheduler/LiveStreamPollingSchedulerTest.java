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
import com.example.monitor.service.RecordingReconciler;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private RecordingReconciler recordingReconciler;

    @Mock
    private com.example.monitor.service.RecordingIntentResolver recordingIntentResolver;

    @Mock
    private com.example.monitor.service.OnlineVideoService onlineVideoService;

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
            verify(streamPlatform, never()).fetchDetails(anyString());
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
            verify(streamPlatform, never()).fetchDetails(anyString());
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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.of(details));

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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.of(details));

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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.empty());

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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.empty());
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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.empty());

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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.empty());

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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.empty());
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
            verify(streamPlatform, never()).fetchDetails(any());
        }

        @Test
        @DisplayName("正常系：タイトルフィルターに一致する配信は録画される")
        void testMethod13() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            target.setRecordEnabled(true);
            target.setRecordTitleKeywords("【ASMR】,【生配信】");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "【ASMR】耳かき音フェチ", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.empty());
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

            verify(streamPlatform, never()).fetchDetails(anyString());
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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.of(details));
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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.of(details));
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
            verify(streamPlatform, never()).fetchDetails(anyString());
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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.of(details));
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
            verify(streamPlatform, never()).fetchDetails(anyString());
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
            verify(streamPlatform, never()).fetchDetails(anyString());
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
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.of(details));
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
            verify(streamPlatform, never()).fetchDetails(anyString());
            verify(notificationDispatcher, never()).notifyLiveStreamStarted(any());
        }

        @Test
        @DisplayName("正常系：詳細情報を取得できなかった場合も失敗回数が加算される")
        void testMethod22() {
            MonitoredChannel target = channel(1L, "UCxxxxxxxx", null);
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(target));
            detects("UCxxxxxxxx", LiveStreamDetection.live("newVideo", "新配信", null, "https://www.youtube.com/watch?v=newVideo"));
            when(streamPlatform.fetchDetails("newVideo")).thenReturn(Optional.empty());

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
        @DisplayName("正常系：巡回のたびに置き去りの録画履歴の補正を行う")
        void testMethod16() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());

            scheduler.pollAllChannels();

            verify(recordingReconciler).reconcileOrphanedRecordings();
        }

        @Test
        @DisplayName("正常系：既に巡回中で見送られた場合は補正処理を呼ばない")
        void testMethod17() {
            AtomicBoolean inProgress =
                    (AtomicBoolean) ReflectionTestUtils.getField(scheduler, "pollingInProgress");
            inProgress.set(true);

            scheduler.pollAllChannels();

            verify(recordingReconciler, never()).reconcileOrphanedRecordings();
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
            verify(recordingReconciler).reconcileOrphanedRecordings();
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
