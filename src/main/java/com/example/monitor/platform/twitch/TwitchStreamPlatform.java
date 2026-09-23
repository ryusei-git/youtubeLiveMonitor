package com.example.monitor.platform.twitch;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.platform.AbstractStreamPlatform;
import com.example.monitor.platform.Platform;
import com.example.monitor.util.EpochTimeConverter;
import com.example.monitor.util.UrlHostMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Twitch 版の {@link com.example.monitor.platform.StreamPlatform} 実装。
 *
 * <h2>YouTube と正反対の事情</h2>
 * YouTube 版は公式 API のクォータ（既定 1 日 10,000）が厳しいため、配信中かどうかの判定に
 * API を使えず {@code /channel/{id}/live} の HTML を解析している。Twitch にはその制約が無い。
 * <ul>
 *   <li>配信状態の問い合わせは<b>クォータを消費しない</b>（分単位のポイント制で、通常は枯渇しない）</li>
 *   <li><b>1 リクエストで 100 チャンネル</b>まとめて問い合わせられる</li>
 * </ul>
 * そのため公式 API をそのまま毎サイクル呼べる。HTML 構造の変化に怯える必要もない。
 *
 * <h2>識別子にユーザー ID を使う理由</h2>
 * Twitch には「ログイン名」（{@code twitch.tv/foo} の {@code foo}）と「ユーザー ID」（数値）がある。
 * <b>ログイン名は配信者本人が変更できる。</b>ログイン名で監視していると、改名された瞬間に
 * 問い合わせが空応答を返し、アプリは「オフラインです」と言い続けて<b>永久に気づけない</b>。
 * そのため {@link #normalizeChannelInput} で必ず不変のユーザー ID へ解決してから保存する。
 *
 * <p>ただし<b>視聴 URL にはログイン名が要る</b>（ユーザー ID では開けない）。ログイン名は
 * 配信状態の応答に毎回含まれるので、検知したその場で URL を組み立てて
 * {@link LiveStreamDetection#watchUrl()} に入れている。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TwitchStreamPlatform extends AbstractStreamPlatform {

    /** {@code twitch.tv/foo} 形式の URL からログイン名を抜き出す。 */
    private static final Pattern LOGIN_IN_URL = Pattern.compile("twitch\\.tv/([^/?#]+)");

    /** 利用者が YouTube の癖で付けてしまいがちな接頭辞。Twitch のログイン名には付かない。 */
    private static final String HANDLE_PREFIX = "@";

    private final TwitchApiClient twitchApiClient;
    private final MonitorProperties monitorProperties;

    @Override
    public Platform platform() {
        return Platform.TWITCH;
    }

    @Override
    public String inputHint() {
        return "URL / チャンネル名（twitch.tv/ の後ろの名前）";
    }

    /**
     * Client ID / Client Secret が設定されていなければ使えない。
     *
     * <p>Twitch は配信状態の問い合わせにも認証が要るため、未設定だと何もできない
     * （YouTube は配信中の判定だけなら認証情報なしで動くので事情が違う）。
     *
     * @return 認証情報が揃っていれば {@code true}
     */
    @Override
    public boolean isAvailable() {
        return monitorProperties.twitch().isConfigured();
    }

    /**
     * 入力されたログイン名・URL を、不変のユーザー ID へ解決する。
     *
     * <p>ログイン名のまま保存すると改名でサイレント故障するため、ここで必ず ID に変換する
     * （このクラスの JavaDoc 参照）。
     *
     * @param rawInput 利用者の入力（{@code https://www.twitch.tv/foo} / {@code foo} / {@code @foo}）
     * @return Twitch のユーザー ID（数値の文字列）
     * @throws IllegalArgumentException 入力が空、またはそのチャンネルが存在しない場合
     */
    @Override
    public String normalizeChannelInput(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new IllegalArgumentException("Twitch のチャンネル名またはURLを入力してください");
        }

        String login = extractLogin(rawInput.trim());
        return twitchApiClient.findUserByLogin(login)
                .map(TwitchUser::id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Twitch にそのチャンネルが見つかりませんでした: " + login));
    }

    /**
     * Twitch の動画 URL かどうかを判定する。
     *
     * @param url 利用者が入力した動画の URL
     * @return Twitch の URL なら {@code true}
     */
    @Override
    public boolean supportsUrl(String url) {
        return UrlHostMatcher.matchesAnyDomain(url, "twitch.tv");
    }

    /**
     * メタデータからチャンネル識別子（ユーザー ID）を求める。
     *
     * <p><b>Twitch の VOD からは {@code channel_id} が取得できない</b>（{@code yt-dlp} は
     * {@code NA} を返すことを実機で確認済み）。得られるのは {@code uploader_id}＝ログイン名だが、
     * このアプリが DB に保存しているのは<b>変更されうるログイン名ではなく不変のユーザー ID</b>
     * （このクラスの JavaDoc 参照）なので、そのままでは突き合わせられない。
     * そこで {@link TwitchApiClient#findUserByLogin}（クォータ消費なし）で解決し直す。
     *
     * <p>解決に失敗しても例外にはしない。Twitch の認証情報が未設定でも
     * <b>ダウンロード自体は {@code yt-dlp} だけで成立する</b>ため、
     * ここで失敗させると「認証情報を入れていないせいで動画も落とせない」ことになるため。
     * その場合はチャンネルに紐づかない録画として記録される。
     *
     * @param source {@code yt-dlp} が返した動画のメタデータ
     * @return Twitch のユーザー ID。解決できなければ {@link Optional#empty()}
     */
    @Override
    public Optional<String> resolveChannelId(VideoSource source) {
        String uploaderId = source.uploaderId();
        if (uploaderId == null) {
            log.warn("Twitch の投稿者を特定できなかったため、チャンネルに紐づけずに扱います: video={}",
                    source.videoId());
            return Optional.empty();
        }

        String login = uploaderId.toLowerCase(Locale.ROOT);
        try {
            return twitchApiClient.findUserByLogin(login).map(TwitchUser::id);
        } catch (RuntimeException e) {
            log.warn("Twitch のログイン名をユーザーIDへ解決できませんでした: login={}", login, e);
            return Optional.empty();
        }
    }

    @Override
    public LiveStreamDetection detectLiveStream(String channelId) {
        return detectLiveStreams(List.of(channelId))
                .getOrDefault(channelId, LiveStreamDetection.failed());
    }

    /**
     * 複数チャンネルの配信状態を<b>1 リクエストでまとめて</b>問い合わせる。
     *
     * <p>既定の実装（1 件ずつ問い合わせる）を上書きしている。Twitch は 100 チャンネルまで
     * まとめられるため、チャンネルが増えても通信回数がほぼ増えない。
     *
     * <p><b>問い合わせが失敗した場合は全件を「判定できなかった」にする。</b>
     * {@code /helix/streams} は配信中のチャンネルしか返さない仕様なので、
     * 「応答が空」と「通信できなかった」は見た目が同じになる。ここで
     * {@link LiveStreamDetection#notLive()} に倒すと、通信障害のたびに全チャンネルが
     * 一斉に「配信していない」と記録され、故障が平常運転に見えてしまう。
     *
     * @param channelIds 正規化済みの Twitch ユーザー ID
     * @return 識別子ごとの判定結果（引数の順序を保つ）
     */
    @Override
    public Map<String, LiveStreamDetection> detectLiveStreams(List<String> channelIds) {
        Map<String, LiveStreamDetection> results = new LinkedHashMap<>();
        if (channelIds.isEmpty()) {
            return results;
        }

        List<TwitchStream> liveStreams;
        try {
            liveStreams = twitchApiClient.fetchLiveStreams(channelIds);
        } catch (RuntimeException e) {
            log.error("Twitch の配信状態をまとめて取得できませんでした: 対象={}件", channelIds.size(), e);
            channelIds.forEach(channelId -> results.put(channelId, LiveStreamDetection.failed()));
            return results;
        }

        // 同じユーザーの配信が複数返ることは無いが、来た場合は先勝ちにして落ちないようにする
        Map<String, TwitchStream> streamsByUserId = liveStreams.stream()
                .collect(Collectors.toMap(TwitchStream::userId, Function.identity(), (first, second) -> first));

        for (String channelId : channelIds) {
            TwitchStream stream = streamsByUserId.get(channelId);
            results.put(channelId, stream == null
                    ? LiveStreamDetection.notLive()
                    : LiveStreamDetection.live(
                            stream.id(), stream.title(), stream.gameName(), stream.watchUrl()));
        }
        return results;
    }

    /**
     * 通知本文に使う配信の詳細を取得する。
     *
     * <p>検知時に得た情報を持ち回らず改めて問い合わせている。Twitch はこの呼び出しに
     * クォータを消費しないため、状態を抱えるより取り直す方が単純で安全なため
     * （YouTube 側はクォータ 1 を消費するので事情が異なる）。
     *
     * @param videoId Twitch の配信 ID
     * @return 取得できた詳細。配信が終わっていた場合や問い合わせに失敗した場合は {@link Optional#empty()}
     */
    @Override
    public Optional<LiveStreamDetails> fetchDetails(String videoId) {
        try {
            return twitchApiClient.findStreamById(videoId).map(this::toLiveStreamDetails);
        } catch (RuntimeException e) {
            log.error("Twitch の配信詳細を取得できませんでした: stream={}", videoId, e);
            return Optional.empty();
        }
    }

    /**
     * 入力文字列からログイン名を取り出す。
     *
     * <p>ログイン名は Twitch 側で小文字に正規化されているため、大文字で入力されても引けるよう
     * 小文字に揃える。
     *
     * @param trimmed 前後の空白を落とした入力
     * @return ログイン名
     */
    private String extractLogin(String trimmed) {
        Matcher matcher = LOGIN_IN_URL.matcher(trimmed);
        String login = matcher.find() ? matcher.group(1) : trimmed;

        if (login.startsWith(HANDLE_PREFIX)) {
            login = login.substring(HANDLE_PREFIX.length());
        }
        return login.toLowerCase(Locale.ROOT);
    }

    /**
     * 配信情報を通知用の詳細へ詰め替える。
     *
     * @param stream Twitch から取得した配信情報
     * @return 通知本文の組み立てに使う詳細
     */
    private LiveStreamDetails toLiveStreamDetails(TwitchStream stream) {
        return LiveStreamDetails.builder()
                .videoId(stream.id())
                .title(stream.title())
                // 項目名は YouTube 由来だが、ここには Twitch のユーザー ID が入る
                .youtubeChannelId(stream.userId())
                .channelTitle(stream.userName())
                // Twitch の配信に説明文は無い。代わりにカテゴリ（ゲーム名）を入れておく
                .description(stream.gameName())
                .actualStartTime(parseStartedAt(stream.startedAt()))
                .thumbnailUrl(stream.resolvedThumbnailUrl())
                .watchUrl(stream.watchUrl())
                .build();
    }

    /**
     * ISO 8601 の配信開始時刻を、アプリ内で使う日時へ変換する。
     *
     * <p>形式が変わっていても通知そのものは送れるべきなので、解釈できない場合は
     * {@code null} を返して処理を続ける（開始時刻は通知本文に必須の項目ではない）。
     *
     * @param startedAt Twitch が返す開始時刻。未設定なら {@code null}
     * @return システムのタイムゾーンで解釈した日時。変換できない場合は {@code null}
     */
    private LocalDateTime parseStartedAt(String startedAt) {
        if (startedAt == null || startedAt.isBlank()) {
            return null;
        }
        try {
            return EpochTimeConverter.toSystemLocalDateTime(Instant.parse(startedAt).toEpochMilli());
        } catch (DateTimeParseException e) {
            log.warn("Twitch の配信開始時刻を解釈できませんでした: {}", startedAt);
            return null;
        }
    }
}
