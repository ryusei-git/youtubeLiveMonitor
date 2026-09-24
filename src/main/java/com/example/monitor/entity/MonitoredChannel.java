package com.example.monitor.entity;

import com.example.monitor.platform.Platform;
import com.example.monitor.util.TitleKeywordMatcher;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 監視対象として登録された YouTube チャンネル。
 *
 * <p>「YouTube 上のチャンネル」そのものではなく、<b>このアプリが監視対象として登録した１件</b>を表す。
 * そのため ID が 2 種類あり、混同しやすいので注意すること。
 * <ul>
 *   <li>{@link #id} … このテーブルの主キー（アプリ内部の連番）。CLI の {@code channel remove -i} で指定するのはこちら。</li>
 *   <li>{@link #youtubeChannelId} … YouTube が発行するチャンネル ID（{@code UC...} で始まる文字列）。</li>
 * </ul>
 *
 * <p>配信状態を表すフィールド（{@link #currentlyLive} 等）は監視サイクルごとに上書きされる
 * 「最新の観測結果」であり、履歴は保持しない。履歴が必要な場合は {@link NotificationHistory} を参照する。
 *
 * @see NotificationHistory
 */
@Entity
@Table(name = "channels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MonitoredChannel {

    /** このテーブルの主キー。YouTube のチャンネル ID ではない点に注意。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * このチャンネルを監視する配信プラットフォーム。
     *
     * <p><b>DB 側の既定値を明示しているのは、既存行があるテーブルへ NOT NULL のカラムを
     * 追加すると「既存行に入れる値がない」という理由で失敗するため</b>
     * （{@code recordEnabled} を追加した際に実際に発生し、カラムが作られないまま
     * 以降すべてのクエリが壊れた。README のスキーマ変更の項を参照）。
     * 既定値を {@code YOUTUBE} にしてあるので、この列を追加する前から登録されていた
     * チャンネルは自動的に YouTube 扱いになり、挙動は変わらない。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'YOUTUBE'")
    private Platform platform = Platform.YOUTUBE;

    /**
     * プラットフォームが発行するチャンネル識別子（YouTube なら {@code UC} から始まる文字列）。
     *
     * <p>DB のカラム名が {@code youtube_channel_id} のままなのは、当初 YouTube 専用だった名残。
     * {@code ddl-auto: update} はカラムのリネームができず、古い NOT NULL カラムが残って
     * INSERT が壊れるため、物理名は変更していない（{@code recordTitleKeywords} と同じ妥協）。
     *
     * <p>ユニーク制約が単独列に掛かっている点にも注意。厳密には
     * {@code (platform, チャンネルID)} の複合であるべきだが、プラットフォームごとに
     * 識別子の形式が異なる（YouTube は {@code UC...}、Twitch は数値）ため実用上は衝突しない。
     */
    @Column(name = "youtube_channel_id", nullable = false, unique = true, length = 64)
    private String youtubeChannelId;

    /** 画面やログでの表示に使うチャンネル名。利用者が任意に付けられるラベルであり、YouTube 上の正式名称と一致する保証はない。 */
    @Column(nullable = false)
    private String channelName;

    /**
     * 最後に通知を送信した配信の動画 ID。まだ一度も通知していなければ {@code null}。
     *
     * <p>重複通知を防ぐための判定材料。配信ごとに動画 ID が変わるため、
     * 「検知した動画 ID がこの値と異なる」ことが「新しい配信である」ことと同義になり、
     * 真偽値フラグのようなリセット処理が不要になる。
     */
    private String lastNotifiedVideoId;

    /** 直近の監視サイクルで配信中と判定されたかどうか。ダッシュボード表示専用で、通知判定には使わない。 */
    private boolean currentlyLive;

    /** 直近の監視サイクルで配信中と判定された動画 ID。配信中でなければ {@code null}。 */
    private String currentLiveVideoId;

    /** Twitchのログイン名はIDから復元できないため、検知時の視聴先を保持する。 */
    @Column(length = 2048)
    private String currentWatchUrl;

    /**
     * 配信開始前の待機所として検知している予約枠の動画ID。予定が無ければ {@code null}。
     *
     * <p>YouTube 限定（Twitch の配信予定は別 API {@code /helix/schedule} が必要で対象外）。
     * {@code LiveStreamDetection.DetectionStatus#UPCOMING} を検知するたびに上書きし、
     * {@code LIVE}（予定が現実になった）または {@code NOT_LIVE}（予定が消えた）を
     * 検知した時点で消す。{@code DETECTION_FAILED} のときは触れない
     * （判定できなかったのに「予定が無い」と記録してしまうと区別が付かなくなる）。
     */
    private String upcomingVideoId;

    /** {@link #upcomingVideoId} の予定タイトル。取得できなかった場合は {@code null}。 */
    private String upcomingTitle;

    /** {@link #upcomingVideoId} の開始予定時刻。取得できなかった場合は {@code null}。 */
    private LocalDateTime upcomingScheduledStartTime;

    /** 通知や録画が行われなかった理由を、ログを掘る前に確認できるようにする。 */
    @Column(length = 2048)
    private String lastDecision;


    /** 直近にこのチャンネルを監視した時刻。この値が更新されていなければ監視ループが止まっている疑いがある。 */
    private LocalDateTime lastCheckedAt;

    /**
     * 直近に配信状態を「正しく判定できた」時刻。判定に失敗した回では更新しない。
     *
     * <p>{@link #lastCheckedAt} は判定の成否にかかわらず毎回更新されるため、
     * これだけでは「巡回はしているが判定は失敗し続けている」状態を見分けられない。
     * 死活監視にはこちらを使う。
     */
    private LocalDateTime lastDetectionSuccessAt;

    /**
     * 配信状態の判定に連続して失敗している回数。判定に成功した時点で 0 に戻る。
     *
     * <p>このアプリの検知は YouTube が公式に保証していない HTML 解析に依存しているため、
     * 構造変更で突然壊れうる。その「サイレント故障」に気づけるようにするための指標で、
     * 値が増え続けているチャンネルはダッシュボードで警告として表示する。
     *
     * <p>{@code columnDefinition} でDB側の既定値を明示しているのは、既に登録済みの
     * チャンネルがある環境で {@code ddl-auto: update} が NOT NULL カラムを追加する際、
     * 既定値がないと既存行に入れる値がなく失敗するため（{@link #recordEnabled} と同じ理由）。
     */
    @Column(columnDefinition = "integer default 0")
    private int consecutiveDetectionFailures;

    /**
     * このチャンネルの配信を検知した際に自動録画するかどうか。既定は録画しない。
     *
     * <p>{@code columnDefinition} でDBのデフォルト値を明示しているのは、この項目を追加した
     * 時点で既に登録済みのチャンネルが存在する環境で {@code ddl-auto: update} が
     * {@code ALTER TABLE ... ADD COLUMN ... NOT NULL} を実行する際、デフォルト値が
     * ないと既存行に値を埋められず失敗するため。
     */
    @Column(columnDefinition = "boolean default false")
    private boolean recordEnabled;

    /**
     * 現在の配信に対して通知の送信に連続して失敗している回数。通知に成功するか、
     * 別の配信を検知した時点で 0 に戻る。
     *
     * <p>通知に失敗したとき「通知済み」にしないことで次のサイクルが自動的に再送信に
     * なる仕組みだが、無制限に繰り返すと <b>失敗履歴が際限なく増え、毎サイクル
     * クォータも消費し続ける</b>（4 時間の配信を 5 分間隔で巡回すれば 48 回試行する）。
     * 一定回数を超えたら同じ配信への再送信を諦めるための回数。
     */
    @Column(columnDefinition = "integer default 0")
    private int notificationFailureCount;

    /**
     * 最後に録画を開始した配信の動画 ID。まだ一度も録画していなければ {@code null}。
     *
     * <p>{@link #lastNotifiedVideoId} と同じ考え方の重複防止策。配信ごとに動画 ID が
     * 変わるため、この値と異なる動画IDを検知した時だけ新しい録画を開始すればよい。
     */
    private String lastRecordedVideoId;

    /**
     * 対象の配信をタイトルで絞り込むためのキーワード（カンマ区切り、いずれか1つでも
     * タイトルに含まれていれば対象、大文字小文字は区別しない）。{@code null}または
     * 空文字なら絞り込みなし（このチャンネルの配信はすべて通知・録画の対象になる）。
     *
     * <p><b>通知と録画の両方に効く。</b>フィールド名が {@code record...} なのは、
     * 当初は録画だけを絞る目的で導入したため。後から「指定したタグがタイトルにない配信は
     * 通知もしたくない」という要望で通知にも適用範囲を広げたが、カラム名の変更は
     * {@code ddl-auto: update} ではリネームされず古いカラムが残る（README のスキーマ変更の項参照）ため、
     * 名前は据え置いている。実際の適用範囲は{@link #matchesFilter(String, String)}を参照。
     *
     * <p>YouTuberがタイトル先頭に付ける「【ASMR】」「【生配信】」のような角括弧タグでの
     * 絞り込みを想定しているが、角括弧自体を特別扱いはせず単純な部分一致で判定する。
     */
    private String recordTitleKeywords;

    /** 監視対象として登録した時刻。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 新規登録用のコンストラクタ。
     *
     * @param youtubeChannelId YouTube が発行するチャンネル ID
     * @param channelName      表示用のチャンネル名
     */
    public MonitoredChannel(String youtubeChannelId, String channelName) {
        this.youtubeChannelId = youtubeChannelId;
        this.channelName = channelName;
    }

    /**
     * 登録時に録画有無まで指定したい場合のコンストラクタ。
     *
     * @param youtubeChannelId YouTube が発行するチャンネル ID
     * @param channelName      表示用のチャンネル名
     * @param recordEnabled    配信を検知した際に自動録画するか
     */
    public MonitoredChannel(String youtubeChannelId, String channelName, boolean recordEnabled) {
        this(youtubeChannelId, channelName);
        this.recordEnabled = recordEnabled;
    }

    /**
     * 登録時に録画有無とタイトルフィルターまで指定したい場合のコンストラクタ。
     *
     * @param youtubeChannelId    YouTube が発行するチャンネル ID
     * @param channelName         表示用のチャンネル名
     * @param recordEnabled       配信を検知した際に自動録画するか
     * @param recordTitleKeywords 録画対象を絞り込むタイトルキーワード（カンマ区切り）。
     *                            {@code null} や空文字なら絞り込みなし
     */
    public MonitoredChannel(String youtubeChannelId, String channelName, boolean recordEnabled,
                            String recordTitleKeywords) {
        this(youtubeChannelId, channelName, recordEnabled);
        this.recordTitleKeywords = recordTitleKeywords;
    }

    /**
     * プラットフォームまで指定して登録する場合のコンストラクタ。
     *
     * <p>{@code channelId} には<b>そのプラットフォームで不変の識別子</b>を渡すこと
     * （YouTube は {@code UC...}、Twitch は数値のユーザー ID）。ハンドルやログイン名を
     * そのまま渡してはならない。解決は {@code StreamPlatform.normalizeChannelInput()} が行う。
     *
     * @param platform            どのプラットフォームのチャンネルか
     * @param channelId           プラットフォームが発行する不変のチャンネル識別子
     * @param channelName         表示用のチャンネル名
     * @param recordEnabled       配信を検知した際に自動録画するか
     * @param recordTitleKeywords 通知・録画の対象を絞り込むタイトルキーワード（カンマ区切り）。
     *                            {@code null} や空文字なら絞り込みなし
     */
    public MonitoredChannel(Platform platform, String channelId, String channelName, boolean recordEnabled,
                            String recordTitleKeywords) {
        this(channelId, channelName, recordEnabled, recordTitleKeywords);
        this.platform = platform;
    }

    /** 登録時刻を自動設定する。JPA が INSERT 直前に呼び出す。 */
    @PrePersist
    void applyCreatedAtOnInsert() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 検知した配信が、このチャンネルの<b>通知・録画の対象</b>かどうかを判定する。
     *
     * <p>{@link #recordTitleKeywords}が未設定なら常に対象。設定されている場合は
     * カンマ区切りのいずれかのキーワードが<b>タイトルかカテゴリのどちらかに</b>
     * 含まれていれば対象（OR条件、大文字小文字は区別しない）。
     *
     * <h4>カテゴリも見る理由</h4>
     * このフィルターは YouTube 向けに作った。VTuber が 1 つのチャンネルで雑談・歌枠・ASMR を
     * 全部やるため、タイトル先頭の「【ASMR】」のようなタグで選り分ける必要があったからである。
     *
     * <p>Twitch には<b>カテゴリという独立した項目</b>があり、内容の申告はそちらに寄る。
     * 実際に Twitch の「ASMR」カテゴリで配信中の 10 人を調べたところ、
     * <b>2 人はタイトルに ASMR を含んでいなかった</b>（例:「IM SLEEPING【SUKITHON DAY 19】」）。
     * タイトルだけを見ていると、こうした配信を取りこぼす。
     *
     * <p><b>タイトルもカテゴリも取得できなかった場合、フィルターが設定済みなら対象外として扱う。</b>
     * 「タグが入っている」と確認できない以上、対象と見なすべきではないという判断
     * （録画については、誤って絞り込み対象外の配信まで録ってしまうより録り逃す方が実害が小さい）。
     * ただし通知では、この分岐に入ることが<b>タイトル取得そのものの故障</b>を意味する場合がある
     * （YouTube 側の HTML 構造が変わると全チャンネルで題名が取れなくなる）。
     * 静かに通知が止まる事態を避けるため、呼び出し側の
     * {@code LiveStreamPollingScheduler} はこの場合を警告としてログに残す。
     *
     * @param title    検知した配信のタイトル。取得できなかった場合は {@code null}
     * @param category 検知した配信のカテゴリ。カテゴリを持たないプラットフォーム
     *                 （YouTube）では {@code null}
     * @return 通知・録画の対象なら {@code true}
     */
    public boolean matchesFilter(String title, String category) {
        return TitleKeywordMatcher.matches(recordTitleKeywords, title, category);
    }
}
