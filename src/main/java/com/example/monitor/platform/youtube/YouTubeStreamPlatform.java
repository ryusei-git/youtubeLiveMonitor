package com.example.monitor.platform.youtube;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.platform.AbstractStreamPlatform;
import com.example.monitor.platform.Platform;
import com.example.monitor.service.LiveStreamDetector;
import com.example.monitor.service.YouTubeApiClient;
import com.example.monitor.util.UrlHostMatcher;
import com.example.monitor.util.YouTubeChannelInputParser;
import com.example.monitor.util.YouTubeWatchUrl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * YouTube 版の {@link com.example.monitor.platform.StreamPlatform} 実装。
 *
 * <p><b>処理そのものは既存クラスへ委譲するだけで、ロジックは一切移していない。</b>
 * プラットフォーム抽象化を導入する段階で挙動が変わると、実稼働中の監視が壊れたかどうかを
 * 切り分けられなくなるため。実際の処理は次の3クラスが持っている。
 * <ul>
 *   <li>{@link LiveStreamDetector} … {@code /channel/{id}/live} の HTML 解析（クォータ消費なし）</li>
 *   <li>{@link YouTubeApiClient} … 通知本文に使う詳細の取得（クォータ 1）</li>
 *   <li>{@link YouTubeChannelInputParser} … URL・ハンドルからチャンネル ID の取り出し</li>
 * </ul>
 *
 * <p>{@link #fallbackDetails} だけは委譲先が無く、ここで検知結果を詰め替えている
 * （詳細の取得に失敗したときに使う代わりの本文）。
 *
 * <p>{@code detectLiveStreams}（まとめて問い合わせ）は既定のまま上書きしない。
 * YouTube はチャンネルごとにページを取得する方式で、まとめる手段が無いため。
 */
@Component
@RequiredArgsConstructor
public class YouTubeStreamPlatform extends AbstractStreamPlatform {

    /** ハンドル（利用者が設定できる名前）を見分けるための接頭辞。 */
    private static final String HANDLE_PREFIX = "@";

    /** タイトルが読めなかったときに入れる題。Discord の埋め込みの題を空にしないため。 */
    private static final String FALLBACK_TITLE = "（配信タイトルを取得できませんでした）";

    private final LiveStreamDetector liveStreamDetector;
    private final YouTubeApiClient youTubeApiClient;

    @Override
    public Platform platform() {
        return Platform.YOUTUBE;
    }

    @Override
    public String inputHint() {
        return "URL / @ハンドル / チャンネルID（UC...）";
    }

    /**
     * 入力された URL・ハンドル・チャンネル ID を、{@code UC...} 形式のチャンネル ID に揃える。
     *
     * <p>ハンドル（{@code @foo}）のまま保存してはならない。{@link LiveStreamDetector} が使う
     * {@code /channel/{id}/live} という URL 形式はハンドルでは機能せず 404 になり、
     * そのチャンネルの監視が静かに効かなくなる（実際に発生した）。
     *
     * @param rawInput 利用者の入力
     * @return {@code UC...} 形式のチャンネル ID
     * @throws IllegalArgumentException 入力が空、またはハンドルに該当するチャンネルが無い場合
     */
    @Override
    public String normalizeChannelInput(String rawInput) {
        String normalized = YouTubeChannelInputParser.normalize(rawInput);
        if (!normalized.startsWith(HANDLE_PREFIX)) {
            return normalized;
        }
        // ハンドルの解決は channels.list(forHandle=...) でクォータを 1 消費する
        return youTubeApiClient.resolveHandleToChannelId(normalized)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ハンドルに該当するチャンネルが見つかりません: " + normalized));
    }

    /**
     * YouTube の動画 URL かどうかを判定する。
     *
     * <p>{@code youtube.com} の通常の視聴 URL・{@code /shorts/}・{@code /live/} に加え、
     * 共有ボタンが作る短縮 URL（{@code youtu.be}）も受け付ける。<b>どの形式でも
     * {@code yt-dlp} が同じ動画 ID に解決できることを実機で確認済み</b>なので、
     * ここでは形の違いを区別する必要がない（ホストだけ見ればよい）。
     *
     * @param url 利用者が入力した動画の URL
     * @return YouTube の URL なら {@code true}
     */
    @Override
    public boolean supportsUrl(String url) {
        return UrlHostMatcher.matchesAnyDomain(url, "youtube.com", "youtu.be");
    }

    /**
     * メタデータからチャンネル ID を取り出す。
     *
     * <p>YouTube は {@code channel_id} が {@code UC...} 形式で、DB に保存している
     * 識別子とそのまま突き合わせられる。<b>{@code uploader_id}（ハンドル）は使わない。</b>
     * ハンドルは本来のチャンネル ID とは別物で、これを保存してしまうと監視が
     * 静かに効かなくなる（{@link #normalizeChannelInput} の JavaDoc 参照）。
     *
     * @param source {@code yt-dlp} が返した動画のメタデータ
     * @return チャンネル ID。取得できなければ {@link Optional#empty()}
     */
    @Override
    public Optional<String> resolveChannelId(VideoSource source) {
        return Optional.ofNullable(source.channelId());
    }

    @Override
    public LiveStreamDetection detectLiveStream(String channelId) {
        // LiveStreamDetector 自身も例外を握って failed() を返すが、
        // 想定外の実行時例外まで拾えるよう safeDetect を通しておく
        return safeDetect(channelId, () -> liveStreamDetector.detectLiveStream(channelId));
    }

    @Override
    public Optional<LiveStreamDetails> fetchDetails(String channelId, String videoId) {
        // 視聴 URL は YouTubeApiClient が詰めている（YouTubeWatchUrl を使用）
        return youTubeApiClient.fetchLiveStreamDetails(videoId);
    }

    /**
     * API で詳細が取れなかったときの代わりの詳細を、検知結果から組み立てる。
     *
     * <p>値の出どころは次のとおり。
     * <ul>
     *   <li>タイトル … 配信ページの HTML から読んだもの（{@code docs/pitfalls.md}「配信タイトルは
     *       {@code /live} ページの HTML から取得できる」）。読めなかったときは {@link #FALLBACK_TITLE}</li>
     *   <li>サムネイル … 動画 ID だけで決まる URL（API の {@code thumbnails.high} と同じ
     *       {@code hqdefault.jpg}）</li>
     *   <li>チャンネル名 … DB の表示名。YouTube 上の正式な名前と違うことがある</li>
     *   <li>視聴 URL … 検知結果が運ぶもの（{@link LiveStreamDetection#watchUrl()}）</li>
     * </ul>
     * 配信状態（{@code broadcastStatus}）・開始時刻・説明文は API でしか取れないので入れない（{@code null} のまま）。
     *
     * @param channelName 通知の送り主として出すチャンネル名（DB の表示名）
     * @param detection   配信中と判定された検知結果
     * @return 検知結果から組み立てた詳細（常に値がある）
     */
    @Override
    public Optional<LiveStreamDetails> fallbackDetails(String channelName, LiveStreamDetection detection) {
        String videoId = detection.videoId();
        return Optional.of(LiveStreamDetails.builder()
                .videoId(videoId)
                .title(detection.title() != null ? detection.title() : FALLBACK_TITLE)
                .channelTitle(channelName)
                .thumbnailUrl(YouTubeWatchUrl.thumbnailOf(videoId))
                .watchUrl(detection.watchUrl())
                .build());
    }
}
