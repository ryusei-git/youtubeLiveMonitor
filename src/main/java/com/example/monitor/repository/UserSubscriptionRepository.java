package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserSubscription;
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
     * @param user 対象の利用者
     * @return 購読日時の降順に並んだ購読一覧
     */
    List<UserSubscription> findByUserOrderBySubscribedAtDesc(AppUser user);

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
