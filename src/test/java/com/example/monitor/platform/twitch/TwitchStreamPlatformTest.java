package com.example.monitor.platform.twitch;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.LiveStreamDetection.DetectionStatus;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.platform.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TwitchStreamPlatform")
class TwitchStreamPlatformTest {

    @Mock
    private TwitchApiClient twitchApiClient;

    @InjectMocks
    private TwitchStreamPlatform platform;

    /**
     * 配信 1 件分のテストデータを作る。
     *
     * @param userId    配信者のユーザー ID
     * @param userLogin 配信者のログイン名
     * @return 組み立てた配信情報
     */
    private TwitchStream stream(String userId, String userLogin) {
        return new TwitchStream(
                "320300019550", userId, userLogin, "テスト配信者", "配信タイトル", "Just Chatting",
                1234, "https://example.com/preview-{width}x{height}.jpg", "2026-09-19T22:04:37Z");
    }

    @Nested
    @DisplayName("platform()")
    class GetPlatform {

        @Test
        @DisplayName("正常系：TWITCHを返す")
        void testMethod01() {
            assertThat(platform.platform()).isEqualTo(Platform.TWITCH);
        }
    }

    @Nested
    @DisplayName("normalizeChannelInput()")
    class NormalizeChannelInput {

        @Test
        @DisplayName("正常系：ログイン名を不変のユーザーIDへ解決する")
        void testMethod01() {
            when(twitchApiClient.findUserByLogin("testuser"))
                    .thenReturn(Optional.of(new TwitchUser("12826", "testuser", "TestUser", null)));

            assertThat(platform.normalizeChannelInput("testuser")).isEqualTo("12826");
        }

        @Test
        @DisplayName("正常系：URLからログイン名を取り出して解決する")
        void testMethod02() {
            when(twitchApiClient.findUserByLogin("testuser"))
                    .thenReturn(Optional.of(new TwitchUser("12826", "testuser", "TestUser", null)));

            assertThat(platform.normalizeChannelInput("https://www.twitch.tv/testuser")).isEqualTo("12826");
        }

        @Test
        @DisplayName("正常系：URLの末尾にタブが付いていてもログイン名だけを取り出す")
        void testMethod03() {
            when(twitchApiClient.findUserByLogin("testuser"))
                    .thenReturn(Optional.of(new TwitchUser("12826", "testuser", "TestUser", null)));

            assertThat(platform.normalizeChannelInput("https://www.twitch.tv/testuser/videos?filter=archives"))
                    .isEqualTo("12826");
        }

        @Test
        @DisplayName("正常系：YouTubeの癖で付けた@は取り除く")
        void testMethod04() {
            when(twitchApiClient.findUserByLogin("testuser"))
                    .thenReturn(Optional.of(new TwitchUser("12826", "testuser", "TestUser", null)));

            assertThat(platform.normalizeChannelInput("@testuser")).isEqualTo("12826");
        }

        @Test
        @DisplayName("正常系：大文字で入力されても小文字に揃えて解決する")
        void testMethod05() {
            when(twitchApiClient.findUserByLogin("testuser"))
                    .thenReturn(Optional.of(new TwitchUser("12826", "testuser", "TestUser", null)));

            assertThat(platform.normalizeChannelInput("TestUser")).isEqualTo("12826");
        }

        @Test
        @DisplayName("異常系：存在しないチャンネルは例外を投げる")
        void testMethod06() {
            when(twitchApiClient.findUserByLogin("notfound")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> platform.normalizeChannelInput("notfound"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("notfound");
        }

        @Test
        @DisplayName("異常系：空の入力は通信せずに例外を投げる")
        void testMethod07() {
            assertThatThrownBy(() -> platform.normalizeChannelInput("  "))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(twitchApiClient);
        }

        @Test
        @DisplayName("正常系：数字だけの入力がログイン名としてだけ見つかれば、そのログインのユーザーIDを返す")
        void testMethod08() {
            when(twitchApiClient.findUserByLogin("12345"))
                    .thenReturn(Optional.of(new TwitchUser("500000001", "12345", "12345", null)));
            when(twitchApiClient.findUsersByIds(List.of("12345"))).thenReturn(List.of());

            assertThat(platform.normalizeChannelInput("12345")).isEqualTo("500000001");
        }

        @Test
        @DisplayName("正常系：数字だけの入力がユーザーIDとしてだけ見つかれば、そのIDを返す")
        void testMethod09() {
            when(twitchApiClient.findUserByLogin("12826")).thenReturn(Optional.empty());
            when(twitchApiClient.findUsersByIds(List.of("12826")))
                    .thenReturn(List.of(new TwitchUser("12826", "testuser", "TestUser", null)));

            assertThat(platform.normalizeChannelInput("12826")).isEqualTo("12826");
        }

        @Test
        @DisplayName("正常系：数字だけの入力がログイン名とIDの両方で同じチャンネルに当たれば、そのIDを返す")
        void testMethod10() {
            TwitchUser user = new TwitchUser("12345", "12345", "12345", null);
            when(twitchApiClient.findUserByLogin("12345")).thenReturn(Optional.of(user));
            when(twitchApiClient.findUsersByIds(List.of("12345"))).thenReturn(List.of(user));

            assertThat(platform.normalizeChannelInput("12345")).isEqualTo("12345");
        }

        @Test
        @DisplayName("異常系：数字だけの入力がログイン名とIDで別のチャンネルに当たれば、URLでの指定を促す例外を投げる")
        void testMethod11() {
            // 実例：xQc のユーザーID 71092938 は、同じ数字をログイン名に持つ別アカウントもあった。
            // どちらかを黙って選ぶと、利用者に別人を購読させてしまう
            when(twitchApiClient.findUserByLogin("71092938"))
                    .thenReturn(Optional.of(new TwitchUser("500000001", "71092938", "71092938", null)));
            when(twitchApiClient.findUsersByIds(List.of("71092938")))
                    .thenReturn(List.of(new TwitchUser("71092938", "xqc", "xQc", null)));

            // 貼られた数字は一覧のユーザーIDである可能性が高いため、IDで当たった方のURLを案内する
            assertThatThrownBy(() -> platform.normalizeChannelInput("71092938"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("https://www.twitch.tv/xqc");
        }

        @Test
        @DisplayName("異常系：数字だけの入力がログイン名でもIDでも見つからなければ例外を投げる")
        void testMethod12() {
            when(twitchApiClient.findUserByLogin("12345")).thenReturn(Optional.empty());
            when(twitchApiClient.findUsersByIds(List.of("12345"))).thenReturn(List.of());

            assertThatThrownBy(() -> platform.normalizeChannelInput("12345"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("見つかりませんでした");
        }

        @Test
        @DisplayName("異常系：数字だけでないログイン名が見つからなければ、IDとしては問い合わせずに例外を投げる")
        void testMethod13() {
            // 数字を含んでいても、数字だけでなければユーザーIDではありえない
            when(twitchApiClient.findUserByLogin("notfound2026")).thenReturn(Optional.empty());

            // 文言も見る：数字の判定を誤ると NumberFormatException（IllegalArgumentException の子）になり、型だけでは区別できない
            assertThatThrownBy(() -> platform.normalizeChannelInput("notfound2026"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("見つかりませんでした");
            verify(twitchApiClient, never()).findUsersByIds(anyList());
        }

        @Test
        @DisplayName("異常系：32ビット整数に収まらない数字はIDとして問い合わせず、ログイン名で見つからなければ例外を投げる")
        void testMethod14() {
            // Twitch は 32 ビット整数に収まらない ID を HTTP 400 で拒む。問い合わせると、
            // 存在しないだけの入力が「見つかりませんでした」ではなくサーバーエラーになる
            String input = String.valueOf(Integer.MAX_VALUE + 1L);
            when(twitchApiClient.findUserByLogin(input)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> platform.normalizeChannelInput(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("見つかりませんでした");
            verify(twitchApiClient, never()).findUsersByIds(anyList());
        }

        @Test
        @DisplayName("正常系：URLや@付きの入力は、中身が数字でもログイン名としてだけ探す")
        void testMethod15() {
            // Twitch の URL はログイン名でしか開けない。曖昧なときに案内する URL で
            // 1 つに決まるよう、ID としては探さない
            when(twitchApiClient.findUserByLogin("12345"))
                    .thenReturn(Optional.of(new TwitchUser("500000001", "12345", "12345", null)));

            assertThat(platform.normalizeChannelInput("https://www.twitch.tv/12345")).isEqualTo("500000001");
            assertThat(platform.normalizeChannelInput("@12345")).isEqualTo("500000001");
            verify(twitchApiClient, never()).findUsersByIds(anyList());
        }
    }

    @Nested
    @DisplayName("supportsUrl()")
    class SupportsUrl {

        @Test
        @DisplayName("正常系：TwitchのVOD URLを担当する")
        void testMethod01() {
            assertThat(platform.supportsUrl("https://www.twitch.tv/videos/2879280908")).isTrue();
        }

        @Test
        @DisplayName("正常系：サブドメインが無いURLも担当する")
        void testMethod02() {
            assertThat(platform.supportsUrl("https://twitch.tv/videos/2879280908")).isTrue();
        }

        @Test
        @DisplayName("異常系：他プラットフォームのURLは担当しない")
        void testMethod03() {
            assertThat(platform.supportsUrl("https://www.youtube.com/watch?v=aqz-KE-bpKQ")).isFalse();
        }
    }

    @Nested
    @DisplayName("resolveChannelId()")
    class ResolveChannelId {

        @Test
        @DisplayName("正常系：ログイン名を不変のユーザーIDへ解決する")
        void testMethod01() {
            // VOD からは channel_id が取れず（NA）、得られるのはログイン名だけ。
            // DB に保存しているのは数値のユーザーIDなので解決しないと突き合わせられない
            when(twitchApiClient.findUserByLogin("caedrel"))
                    .thenReturn(Optional.of(new TwitchUser("92038375", "caedrel", "Caedrel", null)));
            VideoSource source = new VideoSource("twitch:vod", "v2879280908", null, "caedrel", "was_live", "配信タイトル");

            assertThat(platform.resolveChannelId(source)).contains("92038375");
        }

        @Test
        @DisplayName("正常系：大文字で返ってきたログイン名も小文字に揃えて解決する")
        void testMethod02() {
            when(twitchApiClient.findUserByLogin("caedrel"))
                    .thenReturn(Optional.of(new TwitchUser("92038375", "caedrel", "Caedrel", null)));
            VideoSource source = new VideoSource("twitch:vod", "v2879280908", null, "Caedrel", "was_live", "配信タイトル");

            assertThat(platform.resolveChannelId(source)).contains("92038375");
        }

        @Test
        @DisplayName("異常系：投稿者が取得できなかった場合は問い合わせずに空を返す")
        void testMethod03() {
            VideoSource source = new VideoSource("twitch:vod", "v2879280908", null, null, "was_live", "配信タイトル");

            assertThat(platform.resolveChannelId(source)).isEmpty();
            verifyNoInteractions(twitchApiClient);
        }

        @Test
        @DisplayName("異常系：該当するユーザーが居なければ空を返す")
        void testMethod04() {
            when(twitchApiClient.findUserByLogin("unknown")).thenReturn(Optional.empty());
            VideoSource source = new VideoSource("twitch:vod", "v2879280908", null, "unknown", "was_live", "配信タイトル");

            assertThat(platform.resolveChannelId(source)).isEmpty();
        }

        @Test
        @DisplayName("異常系：問い合わせに失敗しても例外にせず空を返す（ダウンロード自体は成立させる）")
        void testMethod05() {
            // 認証情報が未設定でも yt-dlp だけでダウンロードはできる。
            // ここで失敗させると「紐づけられないから動画も落とせない」ことになる
            when(twitchApiClient.findUserByLogin("caedrel"))
                    .thenThrow(new IllegalStateException("Twitch の設定が未完了です"));
            VideoSource source = new VideoSource("twitch:vod", "v2879280908", null, "caedrel", "was_live", "配信タイトル");

            assertThat(platform.resolveChannelId(source)).isEmpty();
        }
    }

    @Nested
    @DisplayName("detectLiveStream()")
    class DetectLiveStream {

        @Test
        @DisplayName("正常系：配信中なら配信IDとタイトルと視聴URLを返す")
        void testMethod01() {
            when(twitchApiClient.fetchLiveStreams(List.of("12826")))
                    .thenReturn(List.of(stream("12826", "testuser")));

            LiveStreamDetection detection = platform.detectLiveStream("12826");

            assertThat(detection.isLive()).isTrue();
            assertThat(detection.videoId()).isEqualTo("320300019550");
            assertThat(detection.title()).isEqualTo("配信タイトル");
            // 配信IDからは作れないので、応答のログイン名から組み立てる
            assertThat(detection.watchUrl()).isEqualTo("https://www.twitch.tv/testuser");
            // カテゴリもタイトルフィルターの判定材料になるため運ぶ
            assertThat(detection.category()).isEqualTo("Just Chatting");
        }

        @Test
        @DisplayName("正常系：応答が空なら配信していないと判定する")
        void testMethod02() {
            when(twitchApiClient.fetchLiveStreams(List.of("12826"))).thenReturn(List.of());

            assertThat(platform.detectLiveStream("12826").status()).isEqualTo(DetectionStatus.NOT_LIVE);
        }

        @Test
        @DisplayName("異常系：問い合わせに失敗した場合は「配信していない」ではなく「判定できなかった」を返す")
        void testMethod03() {
            // ここを notLive にすると、通信障害が「誰も配信していない」平常運転に見えてしまう
            when(twitchApiClient.fetchLiveStreams(anyList()))
                    .thenThrow(new IllegalStateException("Twitch API の呼び出しに失敗しました（HTTP 500）"));

            LiveStreamDetection detection = platform.detectLiveStream("12826");

            assertThat(detection.isDetectionFailed()).isTrue();
            assertThat(detection.status()).isNotEqualTo(DetectionStatus.NOT_LIVE);
        }
    }

    @Nested
    @DisplayName("detectLiveStreams()")
    class DetectLiveStreams {

        @Test
        @DisplayName("正常系：複数チャンネルを1回の問い合わせでまとめて判定する")
        void testMethod01() {
            List<String> channelIds = List.of("111", "222", "333");
            when(twitchApiClient.fetchLiveStreams(channelIds))
                    .thenReturn(List.of(stream("111", "alpha"), stream("333", "gamma")));

            Map<String, LiveStreamDetection> results = platform.detectLiveStreams(channelIds);

            assertThat(results.get("111").isLive()).isTrue();
            assertThat(results.get("111").watchUrl()).isEqualTo("https://www.twitch.tv/alpha");
            // 応答に含まれない＝配信していない
            assertThat(results.get("222").status()).isEqualTo(DetectionStatus.NOT_LIVE);
            assertThat(results.get("333").isLive()).isTrue();
            // まとめて問い合わせるので通信は1回だけ
            verify(twitchApiClient).fetchLiveStreams(channelIds);
        }

        @Test
        @DisplayName("正常系：引数の順序を保った結果を返す")
        void testMethod02() {
            List<String> channelIds = List.of("333", "111", "222");
            when(twitchApiClient.fetchLiveStreams(channelIds)).thenReturn(List.of());

            assertThat(platform.detectLiveStreams(channelIds).keySet())
                    .containsExactly("333", "111", "222");
        }

        @Test
        @DisplayName("正常系：空のリストなら通信せずに空の結果を返す")
        void testMethod03() {
            assertThat(platform.detectLiveStreams(List.of())).isEmpty();

            verifyNoInteractions(twitchApiClient);
        }

        @Test
        @DisplayName("異常系：問い合わせに失敗した場合は全件を「判定できなかった」にする")
        void testMethod04() {
            // まとめ問い合わせは失敗も「まとめて」起きる。ここで notLive に倒すと
            // 通信障害のたびに全チャンネルが一斉に「配信していない」と記録される
            List<String> channelIds = List.of("111", "222", "333");
            when(twitchApiClient.fetchLiveStreams(channelIds))
                    .thenThrow(new IllegalStateException("Twitch API への通信に失敗しました"));

            Map<String, LiveStreamDetection> results = platform.detectLiveStreams(channelIds);

            assertThat(results).hasSize(3);
            assertThat(results.values()).allMatch(LiveStreamDetection::isDetectionFailed);
        }
    }

    @Nested
    @DisplayName("fetchDetails()")
    class FetchDetails {

        @Test
        @DisplayName("正常系：通知本文に必要な情報を詰めて返す")
        void testMethod01() {
            when(twitchApiClient.fetchLiveStreams(List.of("12826")))
                    .thenReturn(List.of(stream("12826", "testuser")));

            LiveStreamDetails details = platform.fetchDetails("12826", "320300019550").orElseThrow();

            assertThat(details.getVideoId()).isEqualTo("320300019550");
            assertThat(details.getTitle()).isEqualTo("配信タイトル");
            assertThat(details.getChannelTitle()).isEqualTo("テスト配信者");
            assertThat(details.getYoutubeChannelId()).isEqualTo("12826");
            assertThat(details.getWatchUrl()).isEqualTo("https://www.twitch.tv/testuser");
            assertThat(details.getActualStartTime()).isNotNull();
        }

        @Test
        @DisplayName("正常系：サムネイルURLのプレースホルダーを実寸に置き換える")
        void testMethod02() {
            when(twitchApiClient.fetchLiveStreams(List.of("12826")))
                    .thenReturn(List.of(stream("12826", "testuser")));

            assertThat(platform.fetchDetails("12826", "320300019550").orElseThrow().getThumbnailUrl())
                    .isEqualTo("https://example.com/preview-640x360.jpg")
                    .doesNotContain("{width}", "{height}");
        }

        @Test
        @DisplayName("正常系：配信が終わっていた場合は空を返す")
        void testMethod03() {
            when(twitchApiClient.fetchLiveStreams(List.of("12826"))).thenReturn(List.of());

            assertThat(platform.fetchDetails("12826", "320300019550")).isEmpty();
        }

        @Test
        @DisplayName("異常系：問い合わせに失敗した場合は例外を投げずに空を返す")
        void testMethod04() {
            when(twitchApiClient.fetchLiveStreams(List.of("12826")))
                    .thenThrow(new IllegalStateException("Twitch API への通信に失敗しました"));

            assertThat(platform.fetchDetails("12826", "320300019550")).isEmpty();
        }

        @Test
        @DisplayName("異常系：開始時刻が解釈できない形式でも通知を止めない")
        void testMethod05() {
            TwitchStream broken = new TwitchStream(
                    "320300019550", "12826", "testuser", "テスト配信者", "配信タイトル", "",
                    0, null, "２０２６年９月１９日");
            when(twitchApiClient.fetchLiveStreams(List.of("12826"))).thenReturn(List.of(broken));

            LiveStreamDetails details = platform.fetchDetails("12826", "320300019550").orElseThrow();

            assertThat(details.getActualStartTime()).isNull();
            assertThat(details.getTitle()).isEqualTo("配信タイトル");
        }

        @Test
        @DisplayName("正常系：チャンネルの配信IDが検知した配信IDと異なる場合は空を返す")
        void testMethod06() {
            // 検知から通知までの間に配信が切り替わった場合、別の配信の詳細で通知しない
            when(twitchApiClient.fetchLiveStreams(List.of("12826")))
                    .thenReturn(List.of(stream("12826", "testuser")));

            assertThat(platform.fetchDetails("12826", "999999999999")).isEmpty();
        }
    }
}
