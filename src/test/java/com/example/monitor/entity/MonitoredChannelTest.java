package com.example.monitor.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MonitoredChannel")
class MonitoredChannelTest {

    @Nested
    @DisplayName("コンストラクタ（youtubeChannelId, channelName）")
    class Constructor {

        @Test
        @DisplayName("正常系：チャンネルIDと名前が設定され、その他の項目はnullまたは初期値になる")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");

            assertThat(channel.getYoutubeChannelId()).isEqualTo("UCxxxxxxxx");
            assertThat(channel.getChannelName()).isEqualTo("テストチャンネル");
            assertThat(channel.getId()).isNull();
            assertThat(channel.isCurrentlyLive()).isFalse();
            assertThat(channel.getLastNotifiedVideoId()).isNull();
            assertThat(channel.isRecordEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("コンストラクタ（youtubeChannelId, channelName, recordEnabled）")
    class ConstructorWithRecordEnabled {

        @Test
        @DisplayName("正常系：録画有効を指定して生成できる")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", true);

            assertThat(channel.isRecordEnabled()).isTrue();
            assertThat(channel.getYoutubeChannelId()).isEqualTo("UCxxxxxxxx");
        }

        @Test
        @DisplayName("正常系：録画無効を指定して生成できる")
        void testMethod02() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル", false);

            assertThat(channel.isRecordEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("applyCreatedAtOnInsert()")
    class ApplyCreatedAtOnInsert {

        @Test
        @DisplayName("正常系：呼び出し時点の時刻がcreatedAtに設定される")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            LocalDateTime before = LocalDateTime.now();

            channel.applyCreatedAtOnInsert();

            LocalDateTime after = LocalDateTime.now();
            assertThat(channel.getCreatedAt()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("matchesFilter()")
    class MatchesFilter {

        @Test
        @DisplayName("正常系：フィルター未設定(null)ならどんなタイトルでも対象になる")
        void testMethod01() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");

            assertThat(channel.matchesFilter("何でもない配信タイトル", null)).isTrue();
        }

        @Test
        @DisplayName("正常系：フィルターが空文字ならどんなタイトルでも対象になる")
        void testMethod02() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("");

            assertThat(channel.matchesFilter("何でもない配信タイトル", null)).isTrue();
        }

        @Test
        @DisplayName("正常系：カンマ区切りの1つ目のキーワードに一致すれば対象になる")
        void testMethod03() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("【ASMR】,【生配信】");

            assertThat(channel.matchesFilter("【ASMR】耳かき音フェチ", null)).isTrue();
        }

        @Test
        @DisplayName("正常系：カンマ区切りの2つ目のキーワードに一致しても対象になる(OR条件)")
        void testMethod04() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("【ASMR】,【生配信】");

            assertThat(channel.matchesFilter("【生配信】雑談します", null)).isTrue();
        }

        @Test
        @DisplayName("正常系：いずれのキーワードにも一致しなければ対象外になる")
        void testMethod05() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("【ASMR】,【生配信】");

            assertThat(channel.matchesFilter("【歌枠】カラオケ配信", null)).isFalse();
        }

        @Test
        @DisplayName("正常系：大文字小文字を区別せずに一致判定する")
        void testMethod06() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("asmr");

            assertThat(channel.matchesFilter("【ASMR】耳かき音フェチ", null)).isTrue();
        }

        @Test
        @DisplayName("異常系：フィルター設定済みでタイトルがnullの場合は対象外になる")
        void testMethod07() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("【ASMR】");

            assertThat(channel.matchesFilter(null, null)).isFalse();
        }

        @Test
        @DisplayName("正常系：タイトルに無くてもカテゴリに一致すれば対象になる")
        void testMethod08() {
            // Twitch は内容の申告がカテゴリ欄に寄るため、タイトルだけ見ると取りこぼす
            // （「ASMR」カテゴリの配信10件中2件はタイトルに ASMR を含まなかった）
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("ASMR");

            assertThat(channel.matchesFilter("IM SLEEPING【SUKITHON DAY 19】", "ASMR")).isTrue();
        }

        @Test
        @DisplayName("正常系：カテゴリに無くてもタイトルに一致すれば対象になる")
        void testMethod09() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("ASMR");

            assertThat(channel.matchesFilter("Live ASMR! Headphones Recommended", "Just Chatting")).isTrue();
        }

        @Test
        @DisplayName("正常系：タイトル・カテゴリのどちらにも無ければ対象外になる")
        void testMethod10() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("ASMR");

            assertThat(channel.matchesFilter("Come be tired with me", "Always On")).isFalse();
        }

        @Test
        @DisplayName("正常系：カテゴリも大文字小文字を区別せずに一致判定する")
        void testMethod11() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("asmr");

            assertThat(channel.matchesFilter("何でもないタイトル", "ASMR")).isTrue();
        }

        @Test
        @DisplayName("正常系：タイトルが取れなくてもカテゴリで判定できれば対象になる")
        void testMethod12() {
            MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
            channel.setRecordTitleKeywords("ASMR");

            assertThat(channel.matchesFilter(null, "ASMR")).isTrue();
        }
    }
}
