package com.example.monitor.service;

import com.example.monitor.dto.DiscoveryCandidateResponse;
import com.example.monitor.dto.DiscoveryStatusResponse;
import com.example.monitor.entity.DiscoveryCandidate;
import com.example.monitor.entity.DiscoveryCandidate.Status;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.platform.youtube.YouTubeStreamPlatform;
import com.example.monitor.repository.DiscoveryCandidateRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.service.DiscoveryYouTubeClient.SearchHit;
import com.example.monitor.util.CaseInsensitiveMatcher;
import com.google.api.services.youtube.model.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 新人 VTuber の発掘：検索で候補を見つけて貯め、友人の判定を受け、30 日の決まりで取り直す（Issue #488）。
 *
 * <h2>判定の順序（安いものから）</h2>
 * 検索（回数を数える）→ 既に知っているチャンネルを落とす（0 単位）→ {@code channels.list} で登録者・動画の数
 * （50 件で 1 単位）→ 語の一致（0 単位）→ 残ったものだけ {@code playlistItems.list}（1 件 1 単位）。
 * 知っているチャンネルには「ちがう」と判定したものも含める。毎回同じ人気チャンネルが出るので、それを覚えておくのが一番効く。
 *
 * <h2>排他</h2>
 * 巡回は定期（{@code DiscoveryScheduler}）と管理者の「今すぐ」の 2 経路から起きるので、{@link #running} で 1 本にする
 * （{@code docs/pitfalls.md}「巡回を起動する経路を増やすなら排他を通す」）。配信の監視ループには入れない。
 */
@Service
@Slf4j
public class DiscoveryService {

    /** 巡回の時刻の基準。1 日 3 回を日本時間で決めているため。 */
    public static final String ZONE = "Asia/Tokyo";

    /**
     * 初回の検索と、打ち切りが続いた語の検索でどこまで遡るか。起動直後でも直近の新人を拾えるように。
     * これより前には広げない。検索は新しい順の 50 件しか取らないので、広げても古い分は結局取れず、回数の割に得が無いため。
     */
    private static final Duration FIRST_LOOKBACK = Duration.ofHours(72);
    /** その語を前回検索できた巡回と重ねる幅。検索結果の反映の遅れで取りこぼさないように。 */
    private static final Duration OVERLAP = Duration.ofHours(1);
    /** 説明を持つ長さ。 */
    private static final int DESCRIPTION_LENGTH = 500;
    /** 一致した語の列の長さ。 */
    private static final int MATCHED_WORDS_LENGTH = 255;

    private final DiscoveryCandidateRepository candidates;
    private final MonitoredChannelRepository monitoredChannels;
    private final DiscoveryYouTubeClient youtube;
    private final YouTubeSearchBudget budget;
    private final YouTubeStreamPlatform youTubePlatform;

    private final List<String> terms;
    private final List<String> words;
    private final long maxSubscribers;
    private final long maxVideos;
    private final Duration refreshAfter;
    private final Duration expireAfter;
    private final boolean scheduled;
    private final String cron;

    private final AtomicBoolean running = new AtomicBoolean();
    /**
     * 検索語ごとの、最後に検索できた巡回の開始時刻。次の回はその語をこの時刻の {@code OVERLAP} 前より後だけ探す。
     *
     * <p>巡回 1 回ぶんの時刻を 1 つだけ持つと、検索の上限・クォータ切れ・API の失敗で飛ばした語の時間帯を、
     * 次の回が探さなくなる（管理者の「今すぐ」で枠を使うと、その日の最後の定期の回が 1 語も検索できないまま
     * 時刻だけ進んでいた）。そのため語ごとに持ち、実際に検索できたときだけ進める。
     * ponytail: メモリだけ。再起動するとすべての語が初回扱い（{@code FIRST_LOOKBACK} 前から）になるが、
     * 既知のチャンネルは落とすので回数の無駄は無い。
     */
    private final Map<String, Instant> lastSearchedAt = new ConcurrentHashMap<>();
    /** 前回の巡回（状態の表示だけに使う。検索する期間は {@code lastSearchedAt} で決める）。起動後に一度も終わっていなければ {@code null}。 */
    private volatile LastRun lastRun;

    /**
     * 1 回の巡回の結果。
     *
     * @param searches 使った検索の回数
     * @param saved    新しく候補にした数
     */
    public record RunResult(int searches, int saved) {}

    /**
     * 前回の巡回の開始時刻と、その回で使った検索の回数。1 つの値にまとめて持ち、表示で時刻と回数が食い違わないようにする。
     *
     * @param startedAt 開始時刻
     * @param searches  使った検索の回数（0 なら、今日の上限で 1 語も検索できなかった）
     */
    private record LastRun(Instant startedAt, int searches) {}

    /**
     * 設定を読み込んで作る。設定の意味は {@code application.yml} の {@code monitor.discovery}。
     *
     * @param candidates        候補の表
     * @param monitoredChannels 監視中のチャンネル
     * @param youtube           API の呼び出し
     * @param budget            検索の回数
     * @param youTubePlatform   チャンネルの入力の正規化
     * @param terms             検索語（カンマ区切り）
     * @param words             VTuber らしさの語（カンマ区切り）
     * @param maxSubscribers    登録者の上限
     * @param maxVideos         動画の数の上限
     * @param refreshDays       取り直すまでの日数
     * @param expireDays        判定されない候補を消すまでの日数
     * @param scheduled         定期の巡回を動かすか
     * @param cron              定期の巡回の時刻
     */
    public DiscoveryService(DiscoveryCandidateRepository candidates, MonitoredChannelRepository monitoredChannels,
                            DiscoveryYouTubeClient youtube, YouTubeSearchBudget budget,
                            YouTubeStreamPlatform youTubePlatform,
                            @Value("${monitor.discovery.terms:}") String terms,
                            @Value("${monitor.discovery.words:}") String words,
                            @Value("${monitor.discovery.max-subscribers:1000}") long maxSubscribers,
                            @Value("${monitor.discovery.max-videos:10}") long maxVideos,
                            @Value("${monitor.discovery.refresh-days:20}") long refreshDays,
                            @Value("${monitor.discovery.expire-days:30}") long expireDays,
                            @Value("${monitor.discovery.enabled:true}") boolean scheduled,
                            @Value("${monitor.discovery.cron:-}") String cron) {
        this.candidates = candidates;
        this.monitoredChannels = monitoredChannels;
        this.youtube = youtube;
        this.budget = budget;
        this.youTubePlatform = youTubePlatform;
        this.terms = split(terms);
        this.words = split(words);
        this.maxSubscribers = maxSubscribers;
        this.maxVideos = maxVideos;
        this.refreshAfter = Duration.ofDays(refreshDays);
        this.expireAfter = Duration.ofDays(expireDays);
        this.scheduled = scheduled;
        this.cron = cron;
    }

    /**
     * 検索語を 1 回ずつ検索し、候補を貯める。
     *
     * <p>検索の前に必ず {@link YouTubeSearchBudget#tryAcquireForDiscovery()} を通す。上限ならその回は打ち切る。
     * {@code quotaExceeded} なら {@link YouTubeSearchBudget#markExhausted()} で今日の検索を止める。
     * ほかの失敗はその語だけ飛ばす（1 語の失敗で残りの語を無駄にしない）。
     *
     * <p>検索する期間は語ごとに決め、検索できた語だけ時刻を進める。打ち切った・失敗した語は、次の回に前回の続きから探す。
     * 語は最後に検索できた時刻の古い順（まだ一度も検索していない語が先）に回す。順番を固定すると、
     * 語の数 × 1 日の回数が発掘の枠を超えたとき、毎回同じ語ばかり打ち切られるため。
     * 例外で抜けた回は {@code lastRun} に記録しない（ERROR のログに残る）。
     *
     * @return 結果。別の巡回が動いていれば空
     */
    public Optional<RunResult> run() {
        if (!running.compareAndSet(false, true)) return Optional.empty();
        try {
            Instant started = Instant.now();
            int searches = 0;
            int saved = 0;
            for (String term : termsOldestFirst()) {
                if (!budget.tryAcquireForDiscovery()) {
                    log.info("発掘の検索が今日の上限に達したので、この回を打ち切ります（残りの語は次の回に続きから探します）");
                    break;
                }
                searches++;
                Instant after = publishedAfter(term, started);
                try {
                    saved += discover(term, after);
                    lastSearchedAt.put(term, started);
                } catch (IOException e) {
                    if (DiscoveryYouTubeClient.isQuotaExceeded(e)) {
                        budget.markExhausted();
                        log.warn("YouTube のクォータを使い切ったので、今日の検索を止めます: term={}", term);
                        break;
                    }
                    log.warn("発掘の検索に失敗しました（次の語へ進みます。この語は次の回も同じ時刻から探します）: term={}, publishedAfter={}, reason={}",
                            term, after, DiscoveryYouTubeClient.describe(e));
                }
            }
            lastRun = new LastRun(started, searches);
            log.info("発掘の巡回を終えました: 検索={}回, 新しい候補={}件", searches, saved);
            return Optional.of(new RunResult(searches, saved));
        } finally {
            running.set(false);
        }
    }

    /**
     * 検索語を、最後に検索できた時刻の古い順に並べる。一度も検索していない語を先にし、同じ時刻なら設定の順のまま
     * （{@link List#sort} は安定な並べ替え）。
     *
     * @return 並べた検索語（設定の {@code terms} は変えない）
     */
    private List<String> termsOldestFirst() {
        List<String> ordered = new ArrayList<>(terms);
        ordered.sort(Comparator.comparing(lastSearchedAt::get, Comparator.nullsFirst(Comparator.naturalOrder())));
        return ordered;
    }

    /**
     * その語で探す期間の始まりを決める。前回検索できた時刻の {@code OVERLAP} 前から。
     * 一度も検索していない語と、打ち切りが続いて {@code FIRST_LOOKBACK} より古くなった語は、{@code FIRST_LOOKBACK} 前から。
     *
     * @param term    検索語
     * @param started この回の開始時刻
     * @return この時刻より後に公開された動画を探す
     */
    private Instant publishedAfter(String term, Instant started) {
        Instant earliest = started.minus(FIRST_LOOKBACK);
        Instant previous = lastSearchedAt.get(term);
        if (previous == null) return earliest;
        Instant after = previous.minus(OVERLAP);
        return after.isBefore(earliest) ? earliest : after;
    }

    /**
     * 1 語を検索し、条件に合うチャンネルを候補として保存する。
     *
     * @param term  検索語
     * @param after この時刻より後の動画だけ
     * @return 保存した数
     * @throws IOException API の呼び出しに失敗した場合
     */
    private int discover(String term, Instant after) throws IOException {
        List<SearchHit> hits = youtube.searchRecentVideos(term, after);
        Map<String, SearchHit> byChannel = new LinkedHashMap<>();
        for (SearchHit hit : hits) byChannel.putIfAbsent(hit.channelId(), hit);
        Set<String> monitored = monitoredIds();
        byChannel.keySet().removeIf(id -> monitored.contains(id) || candidates.existsById(id));

        int small = 0;
        int saved = 0;
        for (Channel channel : youtube.channels(new ArrayList<>(byChannel.keySet()))) {
            if (!isNewcomer(channel)) continue;
            small++;
            SearchHit hit = byChannel.get(channel.getId());
            if (hit == null) continue;
            List<String> matched = matchWords(description(channel) + "\n" + hit.videoTitle());
            if (matched.isEmpty()) continue;
            DiscoveryCandidate candidate = new DiscoveryCandidate();
            candidate.setChannelId(channel.getId());
            fill(candidate, channel);
            candidate.setSampleVideoId(hit.videoId());
            candidate.setSampleVideoTitle(hit.videoTitle());
            candidate.setMatchedWords(joinWords(matched));
            candidate.setFoundByTerm(term);
            candidate.setDiscoveredAt(Instant.now());
            candidate.setFirstUploadAt(firstUpload(channel));
            candidates.save(candidate);
            saved++;
        }
        // 0 件のとき、どの段で落ちたかをログから読めるようにする
        log.info("発掘: term={}, publishedAfter={}, 動画={}件, 知らないチャンネル={}件, 登録者・動画数の条件内={}件, 語が一致して候補にした={}件",
                term, after, hits.size(), byChannel.size(), small, saved);
        return saved;
    }

    /**
     * 毎日の見回り（30 日の決まり、規約 III.E.4.d）。判定されないまま古くなった候補を消し、
     * 取り直しの時期が来た候補・VTuber の API の値をすべて取り直す（チャンネルの値は {@code channels.list}、
     * 見つけた動画のタイトルは {@code videos.list}、最初の投稿日は {@code playlistItems.list}）。
     * 消えたチャンネルは行を消し、削除・非公開になった見つけた動画は値を消す。
     * 一部の値だけ取り直して取り直した時刻を進めると、残りの値が 30 日を超えて残るため。
     *
     * <p>API の呼び出しに失敗したら何も消さない（「消えた」と「確かめられなかった」を区別する）。
     * 途中でクォータを使い切ったら、残りの行は取り直した時刻を進めずに次の見回りへ回す。
     * API を呼んでいる間に判定・手動の登録で状態か取り直した時刻が変わった行は書き戻さない
     * （読んだ時点の値で利用者の判定を消さないため）。
     */
    public synchronized void sweep() {
        Instant now = Instant.now();
        List<DiscoveryCandidate> expired =
                candidates.findByStatusAndDiscoveredAtBefore(Status.CANDIDATE, now.minus(expireAfter));
        candidates.deleteAll(expired);

        List<DiscoveryCandidate> stale = candidates.findByStatusInAndRefreshedAtBefore(
                List.of(Status.CANDIDATE, Status.VTUBER), now.minus(refreshAfter));
        int refreshed = 0;
        int gone = 0;
        if (!stale.isEmpty()) {
            Map<String, Channel> found;
            Map<String, String> sampleTitles;
            try {
                found = youtube.channels(stale.stream().map(DiscoveryCandidate::getChannelId).toList()).stream()
                        .collect(Collectors.toMap(Channel::getId, Function.identity(), (a, b) -> a));
                sampleTitles = youtube.videoTitles(stale.stream().map(DiscoveryCandidate::getSampleVideoId)
                        .filter(Objects::nonNull).distinct().toList());
            } catch (IOException e) {
                log.warn("発掘の候補を取り直せませんでした（次の見回りで再試行します）: reason={}",
                        DiscoveryYouTubeClient.describe(e));
                return;
            }
            for (DiscoveryCandidate candidate : stale) {
                Channel channel = found.get(candidate.getChannelId());
                if (channel == null) {
                    candidates.delete(candidate);
                    gone++;
                    continue;
                }
                Instant firstUploadAt;
                try {
                    firstUploadAt = firstUpload(channel);
                } catch (IOException e) {
                    // firstUpload が投げるのは quotaExceeded だけ。残りの行は取り直した時刻を進めず、次の見回りでやり直す
                    budget.markExhausted();
                    log.warn("YouTube のクォータを使い切ったので、発掘の見回りの取り直しを打ち切ります（残りは次の見回りで取り直します）");
                    break;
                }
                // API を呼んでいる間に判定・手動の登録で変わった行は書き戻さない。見回りの初めに読んだ行を save すると、
                // その間の「ちがう」「VTuber」を古い状態で消してしまう（docs/pitfalls.md「監視ループから save(entity) を呼ばない」と同じ理由）
                DiscoveryCandidate current = candidates.findById(candidate.getChannelId()).orElse(null);
                if (current == null || current.getStatus() != candidate.getStatus()
                        || !Objects.equals(current.getRefreshedAt(), candidate.getRefreshedAt())) {
                    continue;
                }
                fill(current, channel);
                current.setFirstUploadAt(firstUploadAt);
                applySampleTitle(current, sampleTitles);
                candidates.save(current);
                refreshed++;
            }
        }
        log.info("発掘の見回りを終えました: 期限切れで削除={}件, 取り直し={}件, 消えたチャンネルを削除={}件",
                expired.size(), refreshed, gone);
    }

    /**
     * 候補の一覧を、見つけた新しい順で返す。
     *
     * @param status {@code CANDIDATE}（既定）か {@code VTUBER}
     * @return 候補
     * @throws IllegalArgumentException 状態が不正なとき
     */
    public List<DiscoveryCandidateResponse> list(String status) {
        Status parsed = status == null || status.isBlank() ? Status.CANDIDATE : parse(status);
        if (parsed == Status.REJECTED) throw new IllegalArgumentException("一覧の状態は CANDIDATE か VTUBER です");
        Set<String> monitored = monitoredIds();
        return candidates.findByStatusOrderByDiscoveredAtDesc(parsed).stream()
                .map(candidate -> toResponse(candidate, monitored)).toList();
    }

    /**
     * 候補を判定する。候補に戻すときは見つけた日時を今にする（判定されない候補を消す期限を、候補に戻した日から数えるため）。
     * 「ちがう」なら判定以外の値を消す。値を消した行を判定し直すときは、API で取り直す。
     *
     * @param channelId チャンネル ID
     * @param status    {@code VTUBER}・{@code REJECTED}・{@code CANDIDATE}（取り消し）
     * @param username  判定した利用者のログイン ID
     * @return 判定した後の候補
     * @throws IllegalArgumentException 状態が不正なとき
     * @throws NoSuchElementException   候補が無い・チャンネルが消えたとき
     */
    public DiscoveryCandidateResponse decide(String channelId, String status, String username) {
        Status parsed = parse(status);
        DiscoveryCandidate candidate = candidates.findById(channelId)
                .orElseThrow(() -> new NoSuchElementException("候補が見つかりません: " + channelId));
        Status previous = candidate.getStatus();
        if (parsed == Status.REJECTED) {
            candidate.clearApiData();
        } else if (candidate.getRefreshedAt() == null) {
            fill(candidate, fetchChannel(channelId));
            // 値を消した日から数え直す。discoveredAt が空だと 30 日の見回りに掛からないため
            candidate.setDiscoveredAt(Instant.now());
        }
        if (parsed == Status.CANDIDATE && previous != Status.CANDIDATE) {
            // 候補に戻した日から数え直す。見つけた日のままだと、30 日より前に見つけた行は翌朝の見回りで消えるため
            candidate.setDiscoveredAt(Instant.now());
        }
        candidate.setStatus(parsed);
        boolean decided = parsed != Status.CANDIDATE;
        candidate.setDecidedBy(decided ? username : null);
        candidate.setDecidedAt(decided ? Instant.now() : null);
        return toResponse(candidates.save(candidate), monitoredIds());
    }

    /**
     * URL・ハンドル・チャンネル ID から候補を手動で登録する。検索の回数は使わない。
     * 既にある行を足し直したときも、最初の投稿日と見つけた動画のタイトルを取り直す（取り直した時刻が進むため）。
     * 登録者・動画の数・語の条件は掛けない（人が選んだものなので）。「ちがう」だった行は候補に戻す。
     *
     * @param input 利用者の入力
     * @return 登録した候補
     * @throws IllegalArgumentException 入力が空・ハンドルが見つからないとき
     * @throws NoSuchElementException   チャンネルが見つからないとき
     */
    public DiscoveryCandidateResponse add(String input) {
        String channelId = youTubePlatform.normalizeChannelInput(input);
        Channel channel = fetchChannel(channelId);
        DiscoveryCandidate candidate = candidates.findById(channelId).orElseGet(() -> {
            DiscoveryCandidate created = new DiscoveryCandidate();
            created.setChannelId(channelId);
            return created;
        });
        fill(candidate, channel);
        if (candidate.getMatchedWords() == null) candidate.setMatchedWords(joinWords(matchWords(description(channel))));
        if (candidate.getDiscoveredAt() == null) candidate.setDiscoveredAt(Instant.now());
        // fill で取り直した時刻を進めたので、既にある行でも最初の投稿日と見つけた動画を取り直す（30 日の決まり）
        try {
            candidate.setFirstUploadAt(firstUpload(channel));
        } catch (IOException e) {
            throw new IllegalStateException("YouTube のクォータを使い切っています");
        }
        if (candidate.getSampleVideoId() != null) {
            try {
                applySampleTitle(candidate, youtube.videoTitles(List.of(candidate.getSampleVideoId())));
            } catch (IOException e) {
                throw new IllegalStateException("YouTube から動画を取得できません: " + DiscoveryYouTubeClient.describe(e));
            }
        }
        if (candidate.getStatus() == Status.REJECTED) {
            candidate.setStatus(Status.CANDIDATE);
            candidate.setDecidedBy(null);
            candidate.setDecidedAt(null);
        }
        return toResponse(candidates.save(candidate), monitoredIds());
    }

    /**
     * 巡回の状態を返す。
     *
     * @return 前回の時刻と検索の回数・次回の時刻・今日の検索の回数
     */
    public DiscoveryStatusResponse status() {
        Instant next = null;
        if (scheduled && CronExpression.isValidExpression(cron)) {
            ZonedDateTime at = CronExpression.parse(cron).next(ZonedDateTime.now(ZoneId.of(ZONE)));
            next = at == null ? null : at.toInstant();
        }
        LastRun last = lastRun;
        return new DiscoveryStatusResponse(last == null ? null : iso(last.startedAt()),
                last == null ? null : last.searches(), iso(next), budget.discoveryUsedToday(), budget.discoveryLimit());
    }

    private Channel fetchChannel(String channelId) {
        try {
            return youtube.channels(List.of(channelId)).stream().findFirst()
                    .orElseThrow(() -> new NoSuchElementException("チャンネルが見つかりません: " + channelId));
        } catch (IOException e) {
            throw new IllegalStateException("YouTube からチャンネルを取得できません: " + DiscoveryYouTubeClient.describe(e));
        }
    }

    /** 登録者（非公開は残す）と動画の数が上限以下か。 */
    private boolean isNewcomer(Channel channel) {
        var statistics = channel.getStatistics();
        if (statistics == null || statistics.getVideoCount() == null) return false;
        boolean hidden = Boolean.TRUE.equals(statistics.getHiddenSubscriberCount());
        boolean fewSubscribers = hidden || statistics.getSubscriberCount() == null
                || statistics.getSubscriberCount().longValue() <= maxSubscribers;
        return fewSubscribers && statistics.getVideoCount().longValue() <= maxVideos;
    }

    /** チャンネルの値（API で取るもの）を入れ、取り直した時刻を記録する。 */
    private void fill(DiscoveryCandidate candidate, Channel channel) {
        var snippet = channel.getSnippet();
        if (snippet != null) {
            candidate.setTitle(snippet.getTitle());
            candidate.setIconUrl(snippet.getThumbnails() != null && snippet.getThumbnails().getDefault() != null
                    ? snippet.getThumbnails().getDefault().getUrl() : null);
            candidate.setDescription(truncate(snippet.getDescription(), DESCRIPTION_LENGTH));
            candidate.setChannelPublishedAt(DiscoveryYouTubeClient.toInstant(snippet.getPublishedAt()));
        }
        var statistics = channel.getStatistics();
        if (statistics != null) {
            boolean hidden = Boolean.TRUE.equals(statistics.getHiddenSubscriberCount());
            candidate.setSubscriberHidden(hidden);
            candidate.setSubscriberCount(hidden || statistics.getSubscriberCount() == null
                    ? null : statistics.getSubscriberCount().longValue());
            candidate.setVideoCount(statistics.getVideoCount() == null ? null : statistics.getVideoCount().longValue());
        }
        candidate.setRefreshedAt(Instant.now());
    }

    /**
     * アップロードの再生リストで最も古い動画の日時を引く。取れなくても候補にはする（{@code null} のまま）。
     * {@code quotaExceeded} だけは、その後の呼び出しも失敗するので止める。
     */
    private Instant firstUpload(Channel channel) throws IOException {
        var details = channel.getContentDetails();
        if (details == null || details.getRelatedPlaylists() == null || details.getRelatedPlaylists().getUploads() == null) {
            return null;
        }
        try {
            return youtube.oldestUpload(details.getRelatedPlaylists().getUploads()).orElse(null);
        } catch (IOException e) {
            if (DiscoveryYouTubeClient.isQuotaExceeded(e)) throw e;
            log.warn("最初の動画の日時を取れませんでした: channel={}, reason={}", channel.getId(), DiscoveryYouTubeClient.describe(e));
            return null;
        }
    }

    /**
     * 見つけた動画のタイトルを取り直した値にする（30 日の決まり）。動画が削除・非公開になっていれば、見つけた動画ごと消す
     * （古いタイトルを残すと 30 日を超えるため）。
     *
     * @param candidate 候補
     * @param titles    動画 ID → タイトル（{@code DiscoveryYouTubeClient.videoTitles} の結果）
     */
    private static void applySampleTitle(DiscoveryCandidate candidate, Map<String, String> titles) {
        if (candidate.getSampleVideoId() == null) return;
        String title = titles.get(candidate.getSampleVideoId());
        if (title == null) candidate.setSampleVideoId(null);
        candidate.setSampleVideoTitle(title);
    }

    private String description(Channel channel) {
        return channel.getSnippet() == null || channel.getSnippet().getDescription() == null
                ? "" : channel.getSnippet().getDescription();
    }

    /** 語の一覧のうち、文字列に含まれるものを返す。大文字・小文字は区別せず、同じ語は 1 回だけ記録する。 */
    private List<String> matchWords(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))
                    && CaseInsensitiveMatcher.findIgnoreCase(matched, word).isEmpty()) {
                matched.add(word);
            }
        }
        return matched;
    }

    private Set<String> monitoredIds() {
        return monitoredChannels.findAll().stream().map(MonitoredChannel::getYoutubeChannelId).collect(Collectors.toSet());
    }

    private DiscoveryCandidateResponse toResponse(DiscoveryCandidate c, Set<String> monitored) {
        List<String> matched = c.getMatchedWords() == null || c.getMatchedWords().isBlank()
                ? List.of() : split(c.getMatchedWords());
        return new DiscoveryCandidateResponse(c.getChannelId(), c.getTitle(), c.getIconUrl(),
                "https://www.youtube.com/channel/" + c.getChannelId(), c.getSubscriberCount(), c.isSubscriberHidden(),
                c.getVideoCount(), iso(c.getChannelPublishedAt()), iso(c.getFirstUploadAt()), c.getSampleVideoId(),
                c.getSampleVideoTitle(), matched, c.getFoundByTerm(), iso(c.getDiscoveredAt()), iso(c.getRefreshedAt()),
                c.getStatus().name(), c.getDecidedBy(), monitored.contains(c.getChannelId()));
    }

    private static Status parse(String status) {
        try {
            return Status.valueOf(status == null ? "" : status.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("状態は CANDIDATE・VTUBER・REJECTED のどれかです");
        }
    }

    private static String joinWords(List<String> matched) {
        return truncate(String.join(",", matched), MATCHED_WORDS_LENGTH);
    }

    private static List<String> split(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
