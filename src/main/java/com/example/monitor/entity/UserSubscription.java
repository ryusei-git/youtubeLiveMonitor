package com.example.monitor.entity;

import com.example.monitor.util.TitleKeywordMatcher;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 「誰（{@link AppUser}）がどのチャンネル（{@link MonitoredChannel}）を購読しているか」を表す中間テーブル。
 *
 * <p>設計の根拠は {@code docs/user-portal-design.md} 5.2 節。ただし同節が挙げる
 * 「購読者ゼロのチャンネルは巡回対象から外す」は<b>採用していない</b>
 * （{@code docs/user-channel-tasks.md} の「設計書からの変更点」参照）。既存の 19 チャンネルは
 * 購読という概念が無い時代に登録されており、誰にも紐づいていない。巡回条件に購読を混ぜると、
 * この 19 件の監視が本タスクの導入と同時に全停止してしまう。そのため<b>このテーブルは
 * 「画面にどこまで見せるか」だけを表し、巡回・録画・通知の対象を決める役割は持たない</b>。
 * 巡回対象は従来どおり {@link MonitoredChannel} が全件である。
 *
 * <h2>{@code (user_id, channel_id)} の一意制約</h2>
 * 同じ利用者が同じチャンネルを二重に購読できてしまうと、一覧に同じチャンネルが複数回
 * 表示されたり、解除しても購読が残り続けたりする。DB 側で一意制約として強制することで、
 * アプリ側の判定漏れがあってもデータの整合性は壊れないようにしている。
 *
 * <h2>{@link OnDelete} による連鎖削除</h2>
 * 利用者を削除すればその利用者の購読は、チャンネルを削除すればそのチャンネルへの
 * 全員の購読は、それぞれ消えてよい（購読は「誰かが見たがっている」という関係の記録に過ぎず、
 * どちらか一方が居なくなればその関係自体が意味を失うため）。{@link Recording#channel} や
 * {@link NotificationHistory#channel} と同じ考え方で {@code ON DELETE CASCADE} を張っている。
 *
 * <p><b>{@link AuditLog#userId} とは逆の方針である点に注意。</b>監査ログは「証跡」なので
 * 利用者を消しても記録は残さなければならず、あちらは意図的に外部キーを張っていない。
 * こちらは「今の関係」を表すだけなので、関係の一方が消えれば一緒に消えてよい。
 *
 * <h2>チャンネル本体・録画設定はここに持たせない</h2>
 * 購読を解除しても {@link MonitoredChannel} 本体（録画履歴の参照元でもある）は消えない。
 * これは本エンティティに削除ロジックを持たせず、{@code UserSubscriptionRepository} を
 * 「このテーブルの行だけを操作するメソッド」に限定することで担保している。
 * また {@link MonitoredChannel#recordEnabled} や {@link MonitoredChannel#recordTitleKeywords}
 * のような録画設定はチャンネル単位のままここには複製しない。購読ごとに持たせると
 * 「誰かが変えると他の購読者にも影響する」設計との整合が必要になり、それは通知の
 * ユーザー別化（設計書 5.3）と合わせて検討すべき範囲のため、今回のタスク（3-1）では触らない。
 */
@Entity
@Table(name = "user_subscriptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_subscriptions_user_id_channel_id",
                columnNames = {"user_id", "channel_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSubscription {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 購読している利用者。
     *
     * <p>{@link OnDelete} により、利用者を削除すると紐づく購読も DB 側で連鎖削除される
     * （クラス JavaDoc 参照）。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    /**
     * 購読先のチャンネル。
     *
     * <p>{@link OnDelete} により、チャンネルを削除すると紐づく購読も DB 側で連鎖削除される
     * （{@link Recording#channel}・{@link NotificationHistory#channel} と同じ理由）。
     * 逆方向、すなわち購読を削除してもこのチャンネル自体は消えない
     * （クラス JavaDoc の「チャンネル本体はここに持たせない」参照）。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MonitoredChannel channel;

    /** 購読を開始した時刻。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime subscribedAt;

    /**
     * この購読者が自動録画を希望しているか。
     *
     * <p><b>チャンネル単位の {@code MonitoredChannel.recordEnabled} とは別物。</b>
     * 同じチャンネルを複数人が購読できるため、チャンネル側の 1 つの値を共有すると
     * <b>誰かが切った瞬間に他の人の録画も止まる</b>。購読ごとに持たせ、
     * 「誰か 1 人でも希望していれば録画する」と合成する（{@code RecordingIntentResolver} 参照）。
     *
     * <p>既に購読の行が存在する DB へ NOT NULL の boolean を足すと
     * 「既存行に入れる値がない」で ALTER が失敗するため、DB 側の既定値を明示している
     * （CLAUDE.md 参照。実際に発生した事故）。
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean recordEnabled;

    /**
     * この購読者が録画対象を絞り込むキーワード（カンマ区切り）。空なら絞り込みなし。
     *
     * <p>判定はタイトルとカテゴリの両方を見る（{@link TitleKeywordMatcher} 参照）。
     */
    @Column(length = 500)
    private String recordTitleKeywords;

    /** 開始時刻を自動設定する。JPA が INSERT 直前に呼び出す。 */
    @PrePersist
    void applySubscribedAtOnInsert() {
        this.subscribedAt = LocalDateTime.now();
    }

    /**
     * この購読の条件に、その配信が当てはまるかを判定する。
     *
     * @param title    配信タイトル。取得できていなければ {@code null}
     * @param category 配信カテゴリ。プラットフォームに無ければ {@code null}
     * @return 録画の対象とすべきなら {@code true}
     */
    public boolean matchesFilter(String title, String category) {
        return TitleKeywordMatcher.matches(recordTitleKeywords, title, category);
    }
}
