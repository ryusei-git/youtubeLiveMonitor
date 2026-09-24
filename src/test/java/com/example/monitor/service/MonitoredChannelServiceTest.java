package com.example.monitor.service;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.platform.Platform;
import com.example.monitor.platform.StreamPlatform;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonitoredChannelService")
class MonitoredChannelServiceTest {

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private StreamPlatformRegistry streamPlatformRegistry;

    @Mock
    private StreamPlatform streamPlatform;

    @Mock
    private ChannelLogReader channelLogReader;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private AuditLogger auditLogger;

    @InjectMocks
    private MonitoredChannelService monitoredChannelService;

    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        @DisplayName("正常系：リポジトリが返した一覧をそのまま返す")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            when(monitoredChannelRepository.findAll()).thenReturn(List.of(channel));

            List<MonitoredChannel> result = monitoredChannelService.findAll();

            assertThat(result).containsExactly(channel);
        }

        @Test
        @DisplayName("正常系：登録が1件も無い場合は空リストを返す")
        void testMethod02() {
            when(monitoredChannelRepository.findAll()).thenReturn(List.of());

            List<MonitoredChannel> result = monitoredChannelService.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("register()")
    class Register {

        /**
         * 入力の解決をプラットフォーム実装に任せた状態を作る。
         *
         * @param platform     対象のプラットフォーム
         * @param rawInput     利用者の入力
         * @param normalizedId 解決後の識別子
         */
        private void givenPlatformResolves(Platform platform, String rawInput, String normalizedId) {
            when(streamPlatformRegistry.get(platform)).thenReturn(streamPlatform);
            when(streamPlatform.normalizeChannelInput(rawInput)).thenReturn(normalizedId);
        }

        @Test
        @DisplayName("正常系：未登録のチャンネルを登録できる")
        void testMethod01() {
            givenPlatformResolves(Platform.YOUTUBE, "UCxxxxxxxx", "UCxxxxxxxx");
            when(monitoredChannelRepository.existsByYoutubeChannelId("UCxxxxxxxx")).thenReturn(false);
            when(monitoredChannelRepository.save(any(MonitoredChannel.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            MonitoredChannel result = monitoredChannelService.register(
                    Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", false, null);

            assertThat(result.getYoutubeChannelId()).isEqualTo("UCxxxxxxxx");
            assertThat(result.getChannelName()).isEqualTo("テストチャンネル");
            assertThat(result.isRecordEnabled()).isFalse();
            assertThat(result.getPlatform()).isEqualTo(Platform.YOUTUBE);
        }

        @Test
        @DisplayName("正常系：録画有効を指定して登録できる")
        void testMethod02() {
            givenPlatformResolves(Platform.YOUTUBE, "UCxxxxxxxx", "UCxxxxxxxx");
            when(monitoredChannelRepository.existsByYoutubeChannelId("UCxxxxxxxx")).thenReturn(false);
            when(monitoredChannelRepository.save(any(MonitoredChannel.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            MonitoredChannel result = monitoredChannelService.register(
                    Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", true, null);

            assertThat(result.isRecordEnabled()).isTrue();
        }

        @Test
        @DisplayName("異常系：既に登録済みのチャンネルIDを指定するとChannelAlreadyRegisteredExceptionが発生する")
        void testMethod03() {
            givenPlatformResolves(Platform.YOUTUBE, "UCxxxxxxxx", "UCxxxxxxxx");
            when(monitoredChannelRepository.existsByYoutubeChannelId("UCxxxxxxxx")).thenReturn(true);

            assertThatThrownBy(() -> monitoredChannelService.register(
                    Platform.YOUTUBE, "UCxxxxxxxx", "テストチャンネル", false, null))
                    .isInstanceOf(ChannelAlreadyRegisteredException.class);

            verify(monitoredChannelRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系：入力の解決はプラットフォーム実装に任せ、その結果を保存する")
        void testMethod04() {
            // 入力の形（URL・ハンドル・ログイン名）ごとの解釈はプラットフォーム側の責務。
            // このクラスは「解決結果をそのまま保存すること」だけを担保する
            givenPlatformResolves(Platform.YOUTUBE, "https://www.youtube.com/@seldea", "UCresolved00");
            when(monitoredChannelRepository.existsByYoutubeChannelId("UCresolved00")).thenReturn(false);
            when(monitoredChannelRepository.save(any(MonitoredChannel.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            MonitoredChannel result = monitoredChannelService.register(
                    Platform.YOUTUBE, "https://www.youtube.com/@seldea", "セルデア", false, null);

            assertThat(result.getYoutubeChannelId()).isEqualTo("UCresolved00");
        }

        @Test
        @DisplayName("正常系：Twitchを指定するとTwitchのプラットフォーム実装で解決して登録する")
        void testMethod05() {
            givenPlatformResolves(Platform.TWITCH, "https://www.twitch.tv/foo", "12826");
            when(monitoredChannelRepository.existsByYoutubeChannelId("12826")).thenReturn(false);
            when(monitoredChannelRepository.save(any(MonitoredChannel.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            MonitoredChannel result = monitoredChannelService.register(
                    Platform.TWITCH, "https://www.twitch.tv/foo", "テスト配信者", false, null);

            assertThat(result.getPlatform()).isEqualTo(Platform.TWITCH);
            assertThat(result.getYoutubeChannelId()).isEqualTo("12826");
        }

        @Test
        @DisplayName("異常系：入力に該当するチャンネルが無い場合は保存しない")
        void testMethod06() {
            when(streamPlatformRegistry.get(Platform.TWITCH)).thenReturn(streamPlatform);
            when(streamPlatform.normalizeChannelInput("unknown"))
                    .thenThrow(new IllegalArgumentException("Twitch にそのチャンネルが見つかりませんでした: unknown"));

            assertThatThrownBy(() -> monitoredChannelService.register(
                    Platform.TWITCH, "unknown", "テスト", false, null))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(monitoredChannelRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("setRecordEnabled()")
    class SetRecordEnabled {

        @Test
        @DisplayName("正常系：存在するIDを指定すると録画設定が更新される")
        void testMethod01() {
            when(monitoredChannelRepository.existsById(1L)).thenReturn(true);

            monitoredChannelService.setRecordEnabled(1L, true);

            verify(monitoredChannelRepository, times(1)).updateRecordEnabled(1L, true);
        }

        @Test
        @DisplayName("異常系：存在しないIDを指定するとChannelNotFoundExceptionが発生する")
        void testMethod02() {
            when(monitoredChannelRepository.existsById(999L)).thenReturn(false);

            assertThatThrownBy(() -> monitoredChannelService.setRecordEnabled(999L, true))
                    .isInstanceOf(ChannelNotFoundException.class);

            verify(monitoredChannelRepository, never()).updateRecordEnabled(any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("setRecordTitleKeywords()")
    class SetRecordTitleKeywords {

        @Test
        @DisplayName("正常系：存在するIDを指定するとタイトルフィルターが更新される")
        void testMethod01() {
            when(monitoredChannelRepository.existsById(1L)).thenReturn(true);

            monitoredChannelService.setRecordTitleKeywords(1L, "【ASMR】,【生配信】");

            verify(monitoredChannelRepository, times(1)).updateRecordTitleKeywords(1L, "【ASMR】,【生配信】");
        }

        @Test
        @DisplayName("異常系：存在しないIDを指定するとChannelNotFoundExceptionが発生する")
        void testMethod02() {
            when(monitoredChannelRepository.existsById(999L)).thenReturn(false);

            assertThatThrownBy(() -> monitoredChannelService.setRecordTitleKeywords(999L, "【ASMR】"))
                    .isInstanceOf(ChannelNotFoundException.class);

            verify(monitoredChannelRepository, never()).updateRecordTitleKeywords(any(), any());
        }
    }

    @Nested
    @DisplayName("remove()")
    class Remove {

        @Test
        @DisplayName("正常系：存在するIDを指定するとDBの行とチャンネル別ログの両方が削除される")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setId(1L);
            when(monitoredChannelRepository.findById(1L)).thenReturn(Optional.of(channel));

            monitoredChannelService.remove(1L);

            verify(monitoredChannelRepository, times(1)).deleteById(eq(1L));
            verify(channelLogReader, times(1)).deleteChannelLogs("UCxxxxxxxx");
        }

        @Test
        @DisplayName("異常系：存在しないIDを指定するとChannelNotFoundExceptionが発生し、削除は行われない")
        void testMethod02() {
            when(monitoredChannelRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> monitoredChannelService.remove(999L))
                    .isInstanceOf(ChannelNotFoundException.class);

            verify(monitoredChannelRepository, never()).deleteById(any());
            verify(channelLogReader, never()).deleteChannelLogs(any());
        }
    }
}
