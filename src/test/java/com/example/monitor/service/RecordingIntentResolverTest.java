package com.example.monitor.service;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.repository.UserSubscriptionRepository;
import com.example.monitor.service.RecordingIntentResolver.RecordingIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingIntentResolver")
class RecordingIntentResolverTest {

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @InjectMocks
    private RecordingIntentResolver resolver;

    /**
     * 録画を希望している購読を作る。リポジトリは録画を希望している購読だけを返すので、常に recordEnabled を true にする。
     *
     * @param keywords 購読者の条件キーワード（カンマ区切り）。null なら条件なし
     */
    private static UserSubscription wanting(String keywords) {
        return UserSubscription.builder().recordEnabled(true).recordTitleKeywords(keywords).build();
    }

    @Nested
    @DisplayName("resolve()")
    class Resolve {

        @Test
        @DisplayName("正常系：チャンネル単位で希望し条件に合えば、購読を読まずに「希望あり・一致」を返す")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", true, "ASMR");

            RecordingIntent result = resolver.resolve(channel, "【ASMR】耳かき", null);

            assertThat(result).isEqualTo(new RecordingIntent(true, true));
            verifyNoInteractions(userSubscriptionRepository);
        }

        @Test
        @DisplayName("正常系：チャンネル単位で希望していなくても、条件なしの購読者が1人いれば「希望あり・一致」を返す")
        void testMethod02() {
            // A さんは ASMR だけ、B さんは全部。A さんの条件で B さんの録画まで絞ってはならない
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", false);
            when(userSubscriptionRepository.findByChannelAndRecordEnabledTrue(channel))
                    .thenReturn(List.of(wanting("ASMR"), wanting(null)));

            RecordingIntent result = resolver.resolve(channel, "【歌枠】カラオケ", null);

            assertThat(result).isEqualTo(new RecordingIntent(true, true));
        }

        @Test
        @DisplayName("正常系：希望者はいるが誰の条件にも合わなければ「希望あり・不一致」を返す")
        void testMethod03() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", false);
            when(userSubscriptionRepository.findByChannelAndRecordEnabledTrue(channel))
                    .thenReturn(List.of(wanting("ASMR")));

            RecordingIntent result = resolver.resolve(channel, "【歌枠】カラオケ", null);

            assertThat(result).isEqualTo(new RecordingIntent(true, false));
        }

        @Test
        @DisplayName("正常系：チャンネル単位も購読者も希望していなければ「希望なし・不一致」を返す")
        void testMethod04() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", false);
            when(userSubscriptionRepository.findByChannelAndRecordEnabledTrue(channel)).thenReturn(List.of());

            RecordingIntent result = resolver.resolve(channel, "【歌枠】カラオケ", null);

            assertThat(result).isEqualTo(new RecordingIntent(false, false));
        }

        @Test
        @DisplayName("正常系：チャンネル単位で希望していれば、条件に合わず購読者がいなくても「希望あり・不一致」を返す")
        void testMethod05() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", true, "ASMR");
            when(userSubscriptionRepository.findByChannelAndRecordEnabledTrue(channel)).thenReturn(List.of());

            RecordingIntent result = resolver.resolve(channel, "【歌枠】カラオケ", null);

            assertThat(result).isEqualTo(new RecordingIntent(true, false));
        }

        @Test
        @DisplayName("正常系：チャンネル単位の条件に合わなくても、条件に合う購読者がいれば「希望あり・一致」を返す")
        void testMethod06() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", true, "ASMR");
            when(userSubscriptionRepository.findByChannelAndRecordEnabledTrue(channel))
                    .thenReturn(List.of(wanting("歌枠")));

            RecordingIntent result = resolver.resolve(channel, "【歌枠】カラオケ", null);

            assertThat(result).isEqualTo(new RecordingIntent(true, true));
        }

        @Test
        @DisplayName("正常系：タイトルが取れなくても、カテゴリが購読者の条件に合えば「希望あり・一致」を返す")
        void testMethod07() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", false);
            when(userSubscriptionRepository.findByChannelAndRecordEnabledTrue(channel))
                    .thenReturn(List.of(wanting("ASMR")));

            RecordingIntent result = resolver.resolve(channel, null, "ASMR");

            assertThat(result).isEqualTo(new RecordingIntent(true, true));
        }

        @Test
        @DisplayName("異常系：タイトルもカテゴリも無く、条件付きの購読者しかいなければ「希望あり・不一致」を返す")
        void testMethod08() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", false);
            when(userSubscriptionRepository.findByChannelAndRecordEnabledTrue(channel))
                    .thenReturn(List.of(wanting("ASMR")));

            RecordingIntent result = resolver.resolve(channel, null, null);

            assertThat(result).isEqualTo(new RecordingIntent(true, false));
        }
    }
}
