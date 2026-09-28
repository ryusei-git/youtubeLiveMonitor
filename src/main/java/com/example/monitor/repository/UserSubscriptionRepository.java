package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserSubscription;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 購読（{@link UserSubscription}）の永続化を担当するリポジトリ。
 *
 * <p><b>このインターフェースが公開するのは {@code user_subscriptions} テーブルだけを
 * 操作するメソッドに限る。</b>購読を解除しても {@link MonitoredChannel} 本体・
 * 他の利用者の購読・録画履歴・通知履歴には一切触れてはならない
 * （{@code docs/user-channel-tasks.md} の「絶対に守ること」参照）。{@link #deleteByUserAndChannel}
 * を素の {@code DELETE FROM user_subscriptions ...} で実装しているのはこのため。
 */
public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    /**
     * 指定した利用者の購読一覧を、購読した日時の新しい順に取得する。
     *
     * <p>チャンネルも同時に読み込む（{@code @EntityGraph}）。呼び出し元（購読の一覧・配信予定）は
     * 全件のチャンネル名と配信予定を読むので、遅延読み込みのままだと購読の件数（最大 50）だけ
     * 問い合わせが追加で走る。
     *
     * @param user 対象の利用者
     * @return 購読日時の降順に並んだ購読一覧（チャンネル読み込み済み）
     */
    @EntityGraph(attributePaths = "channel")
    List<UserSubscription> findByUserOrderBySubscribedAtDesc(AppUser user);

    /**
     * この利用者の購読件数を数える。上限の判定に使う。
     *
     * <p>一覧を取得して数えないのは、判定のためだけに全件を読み込むのが無駄なため。
     *
     * @param user 対象の利用者
     * @return 購読件数
     */
    long countByUser(AppUser user);

    /**
     * 指定した利用者が指定したチャンネルを既に購読しているかを判定する。
     *
     * <p>二重購読を防ぐための事前確認に使う（DB 側の一意制約が最終的な保証だが、
     * 「既に購読済みです」のような分かりやすいエラーを返すにはアプリ側でも確認が要る）。
     *
     * @param user    対象の利用者
     * @param channel 対象のチャンネル
     * @return 購読済みなら {@code true}
     */
    boolean existsByUserAndChannel(AppUser user, MonitoredChannel channel);

    /**
     * このチャンネルを購読していて、かつ自動録画を希望している購読を返す。
     *
     * <p>「誰か 1 人でも希望していれば録画する」を判定するために使う
     * （{@code RecordingIntentResolver}）。希望していない購読まで読まないのは、
     * 巡回サイクルの中で毎回呼ばれるため。
     *
     * @param channel 対象のチャンネル
     * @return 録画を希望している購読
     */
    List<UserSubscription> findByChannelAndRecordEnabledTrue(MonitoredChannel channel);

    /**
     * ログイン中の利用者のこのチャンネルへの購読を引く。録画設定の変更に使う。
     *
     * @param user    利用者
     * @param channel チャンネル
     * @return 購読。していなければ {@link java.util.Optional#empty()}
     */
    java.util.Optional<UserSubscription> findByUserAndChannel(AppUser user, MonitoredChannel channel);

    /**
     * チャンネルごとの購読者数をまとめて数える。
     *
     * <p>管理者のチャンネル一覧に件数を添えるために使う（チャンネルを削除すると購読も連鎖で消えるため、
     * 誰かが購読しているかを削除前に見分けられるようにする）。チャンネルごとに問い合わせると
     * 登録数に比例してクエリが増えるため、1 回の GROUP BY でまとめて数える。
     *
     * @return 各要素が {@code [チャンネルの主キー(Long), 件数(Long)]} の配列。購読が 1 件も無いチャンネルは含まない
     */
    @Query("SELECT s.channel.id, COUNT(s) FROM UserSubscription s GROUP BY s.channel.id")
    List<Object[]> countByChannel();

    /**
     * 全チャンネルの購読者名を、チャンネルの主キーと組にして返す。
     *
     * <p>管理者のチャンネル一覧に「誰が購読しているか」を添えるために使う。{@link #countByChannel()} と同じく、
     * チャンネルごとに問い合わせると登録数に比例してクエリが増えるため、1 回でまとめて取る。
     * 利用者名の順で並べるのは、画面で毎回同じ順に並ぶようにするため。
     *
     * @return 各要素が {@code [チャンネルの主キー(Long), 利用者名(String)]} の配列。購読が 1 件も無いチャンネルは含まない
     */
    @Query("SELECT s.channel.id, s.user.username FROM UserSubscription s ORDER BY s.user.username")
    List<Object[]> findSubscriberNamesByChannel();

    /**
     * 指定した利用者と指定したチャンネルの組み合わせの購読を削除する。
     *
     * <p><b>{@code user_subscriptions} テーブルの行を直接 DELETE するだけで、
     * {@link MonitoredChannel} や他の利用者の購読には一切触れない。</b>
     * 「購読を消してもチャンネル本体は消えない」という要件を、JPQL の対象を
     * このテーブルだけに絞ることで担保している。
     *
     * @param user    対象の利用者
     * @param channel 対象のチャンネル
     * @return 削除した件数。購読していなければ 0
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM UserSubscription s WHERE s.user = :user AND s.channel = :channel")
    int deleteByUserAndChannel(@Param("user") AppUser user, @Param("channel") MonitoredChannel channel);
}
