package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.SearchProperties;
import com.example.monitor.entity.VideoCollectionQuota;
import com.example.monitor.exception.SearchQuotaExceededException;
import com.example.monitor.repository.VideoCollectionQuotaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * YouTube の検索（{@code search.list}）の回数を数え、全体・用途ごと・1 人ごとの上限で止める。
 *
 * <h2>なぜ既定値が 52 回（発掘 12 回・その場の検索 40 回・1 人 10 回）か</h2>
 * 2026-06-01 から {@code search.list} は 1 回 1 単位の専用の枠（1 日 100 回）に変わったが、
 * 自分のプロジェクトがどちらの数え方かは Cloud Console で確かめるまで分からない。
 * そこで<b>旧方式（1 回 100 単位、1 日 10,000 単位の共有の枠）でも超えない値</b>を既定にした。
 * 52 回 × 100 単位 = 5,200 単位で、動画の収集（{@link YouTubeCatalogQuota}）の 3,000 単位と
 * 合わせても 8,200 単位。残りの 1,800 単位を監視・通知の呼び出しに残せる。
 * 新方式と確かめられたら {@code .env} で増やせばよい。
 *
 * <h2>なぜ枠を分けるか</h2>
 * 発掘の枠（{@code discovery-limit}）は、その場の検索の上限を {@code daily-limit - discovery-limit}
 * にして確保する。友人が昼に検索を使い切っても、定期の発掘が止まらないようにするため。
 * 1 人あたりの上限は、1 人が全員分の枠を使い切らないようにするため。
 * 管理者のチャンネル名検索も対話の枠から使う（{@link #acquireForAdmin()}）。発掘の枠を食わせないため。
 *
 * <h2>数え方</h2>
 * {@link YouTubeCatalogQuota} と同じく {@code VIDEO_COLLECTION_QUOTA} の表に行を足して数える
 * （再起動しても数え直しにならない）。行の id は、全体 {@code youtube-search}・
 * 対話 {@code youtube-search-interactive}・発掘 {@code youtube-search-discovery}・
 * 利用者ごと {@code youtube-search-user:<username>}。日付の区切りは米国太平洋時間の 0 時
 * （YouTube のクォータが戻る時刻）。
 * CLI（{@code channel search}）も同じ表の同じ行を数える（H2 を {@code AUTO_SERVER=TRUE} で開いているので、
 * 常駐のサービスと同じ DB を読み書きする）。ただし {@code synchronized} は JVM をまたいで効かない
 * （{@link #acquireForAdmin()} を参照）。
 *
 * <p>定期実行から呼んでよいのは {@link #tryAcquireForDiscovery()} だけ
 * （{@code docs/pitfalls.md}「クォータを消費する API を監視ループに入れない」）。
 *
 * <p><b>{@code search.list} を呼ぶ箇所を増やすときは、必ずこのクラスの取得のどれかを通す。</b>
 * 管理者のチャンネル名検索が数えられずに抜けていたことがある。
 */
@Component
public class YouTubeSearchBudget {

    /** 日付を切るタイムゾーン。YouTube Data API のクォータは太平洋時間の 0 時に戻るため。 */
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    /** 全体の行。対話と発掘の両方がここを数えるので、合計が {@code daily-limit} を超えない。 */
    private static final String TOTAL_ID = "youtube-search";
    /** その場の検索の行。 */
    private static final String INTERACTIVE_ID = "youtube-search-interactive";
    /** 定期の発掘の行。 */
    private static final String DISCOVERY_ID = "youtube-search-discovery";
    /** 利用者ごとの行の接頭辞。後ろにログイン ID を付ける。 */
    private static final String USER_ID_PREFIX = "youtube-search-user:";

    private final VideoCollectionQuotaRepository repository;
    private final SearchProperties limits;

    /**
     * 上限の設定を読み込んで作る。
     *
     * @param repository 回数を保存する表
     * @param properties 上限の設定（{@code monitor.youtube.search.*}）
     */
    public YouTubeSearchBudget(VideoCollectionQuotaRepository repository, MonitorProperties properties) {
        this.repository = repository;
        this.limits = properties.youtube().search();
    }

    /**
     * その場の検索を 1 回使う前に、全体・対話・その利用者の回数を 1 ずつ増やす。
     *
     * <p>3 つの行をすべて確かめてから増やす。1 つでも上限なら、どれも増やさない
     * （弾いた検索で残りが減ると、利用者から見て回数が合わなくなるため）。
     *
     * @param username 検索する利用者のログイン ID
     * @throws SearchQuotaExceededException どれかが本日の上限に達している場合
     */
    public synchronized void acquireForUser(String username) {
        LocalDate today = today();
        VideoCollectionQuota total = load(TOTAL_ID, today);
        VideoCollectionQuota interactive = load(INTERACTIVE_ID, today);
        VideoCollectionQuota user = load(USER_ID_PREFIX + username, today);
        if (total.getRequests() >= limits.dailyLimit()
                || interactive.getRequests() >= interactiveLimit()
                || user.getRequests() >= limits.perUserLimit()) {
            throw new SearchQuotaExceededException();
        }
        increment(total);
        increment(interactive);
        increment(user);
    }

    /**
     * 管理者のチャンネル名検索（管理画面の {@code GET /api/channels/search} と CLI の {@code channel search}）が
     * 検索を 1 回使う前に、全体と対話の回数を 1 ずつ増やす。
     *
     * <p>数えないと全体の上限（{@code daily-limit}）の前提が崩れる。旧方式なら 1 回 100 単位で、監視・通知に
     * 残した単位を食う。新方式でも専用の 1 日 100 回の枠を数えずに使う。どちらでも、利用者の検索が先に YouTube の
     * {@code quotaExceeded} を受け、{@link #markExhausted()} でその日の検索がすべて止まる。
     *
     * <p>対話の行も数えるのは、発掘の枠（{@code discovery-limit}）を管理者の検索にも食わせないため。
     * 1 人ごとの行を数えないのは、CLI にはログイン ID が無く、1 人ごとの上限は友人どうしで枠を分け合うための
     * 決まりだから。利用者の画面の「今日の残り」（{@link #status(String)}）も、管理者が使った分だけ減る。
     *
     * <p>CLI は常駐のサービスと別の JVM で動くので、{@code synchronized} は両者の間では効かない。
     * 同じ瞬間に両方が数えると、1 回ぶん数え漏れうる（行を読んでから保存するまでの間に割り込まれるため）。
     * 手動の検索が偶然重なったときだけで、ずれても YouTube の {@code quotaExceeded} を受けた
     * {@link #markExhausted()} が最後の歯止めになるので、DB の条件付き更新にはしていない。
     *
     * @throws SearchQuotaExceededException 全体か対話の回数が本日の上限に達している場合
     */
    public synchronized void acquireForAdmin() {
        LocalDate today = today();
        VideoCollectionQuota total = load(TOTAL_ID, today);
        VideoCollectionQuota interactive = load(INTERACTIVE_ID, today);
        if (total.getRequests() >= limits.dailyLimit() || interactive.getRequests() >= interactiveLimit()) {
            throw new SearchQuotaExceededException();
        }
        increment(total);
        increment(interactive);
    }

    /**
     * 定期の新人発掘が検索を 1 回使う前に、全体と発掘の回数を 1 ずつ増やす。
     *
     * <p>例外にしないのは、発掘は上限に達したら次の巡回まで待てばよく、失敗として扱う理由がないため。
     *
     * @return 使ってよければ {@code true}（回数を増やした）。上限なら {@code false}（増やさない）
     */
    public synchronized boolean tryAcquireForDiscovery() {
        LocalDate today = today();
        VideoCollectionQuota total = load(TOTAL_ID, today);
        VideoCollectionQuota discovery = load(DISCOVERY_ID, today);
        if (total.getRequests() >= limits.dailyLimit() || discovery.getRequests() >= limits.discoveryLimit()) {
            return false;
        }
        increment(total);
        increment(discovery);
        return true;
    }

    /**
     * API が 403 の {@code quotaExceeded} を返したときに呼び、その日の検索をすべて止める。
     *
     * <p>こちらの数えた回数と YouTube 側の実際の消費は、ほかの呼び出しや数え方の違いでずれうる。
     * YouTube が「使い切った」と言った以上、その日は叩いても失敗するだけなので、
     * 全体の行を上限の値にして以後の検索（対話・発掘の両方）を止める。
     */
    public synchronized void markExhausted() {
        VideoCollectionQuota total = load(TOTAL_ID, today());
        total.setRequests(Math.max(total.getRequests(), limits.dailyLimit()));
        repository.saveAndFlush(total);
    }

    /**
     * 画面に出す「今日の残り」を返す。回数は増やさない。
     *
     * @param username 画面を見ている利用者のログイン ID
     * @return 対話の残り・その人の残り・リセットの時刻
     */
    public synchronized SearchQuotaStatus status(String username) {
        LocalDate today = today();
        int totalLeft = limits.dailyLimit() - load(TOTAL_ID, today).getRequests();
        int interactiveLeft = Math.max(0, Math.min(totalLeft,
                interactiveLimit() - load(INTERACTIVE_ID, today).getRequests()));
        int userLeft = Math.max(0, Math.min(interactiveLeft,
                limits.perUserLimit() - load(USER_ID_PREFIX + username, today).getRequests()));
        Instant resetsAt = today.plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant();
        return new SearchQuotaStatus(interactiveLeft, userLeft, resetsAt);
    }

    /**
     * 定期の発掘が今日使った検索の回数を返す（発掘の画面の表示用。回数は増やさない）。
     *
     * @return 太平洋時間の今日の発掘の回数
     */
    public synchronized int discoveryUsedToday() {
        return load(DISCOVERY_ID, today()).getRequests();
    }

    /**
     * 定期の発掘の 1 日の上限を返す。
     *
     * @return {@code monitor.youtube.search.discovery-limit}
     */
    public int discoveryLimit() {
        return limits.discoveryLimit();
    }

    /**
     * その場の検索の上限を返す。発掘の枠を差し引いた残りなので、設定が逆転しても負にしない。
     *
     * @return その場の検索の 1 日の上限
     */
    private int interactiveLimit() {
        return Math.max(0, limits.dailyLimit() - limits.discoveryLimit());
    }

    private LocalDate today() {
        return LocalDate.now(QUOTA_ZONE);
    }

    /**
     * 行を読み、日付が変わっていれば回数を 0 に戻す（保存は増やすときだけ）。
     *
     * @param id    行の id
     * @param today 太平洋時間の今日
     * @return 今日の回数が入った行
     */
    private VideoCollectionQuota load(String id, LocalDate today) {
        VideoCollectionQuota state = repository.findById(id).orElseGet(VideoCollectionQuota::new);
        state.setId(id);
        if (!today.equals(state.getQuotaDate())) {
            state.setQuotaDate(today);
            state.setRequests(0);
        }
        return state;
    }

    private void increment(VideoCollectionQuota state) {
        state.setRequests(state.getRequests() + 1);
        repository.saveAndFlush(state);
    }
}
