package com.example.monitor.repository;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.NotificationHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;

/**
 * 通知履歴の永続化を担当するリポジトリ。
 *
 * <p>メソッド名は Spring Data JPA の命名規約に従っており、名前そのものがクエリの定義になっている。
 * そのため自由な改名はできない（改名すると意図したクエリが生成されなくなる）。
 */
public interface NotificationHistoryRepository
        extends JpaRepository<NotificationHistory, Long>, JpaSpecificationExecutor<NotificationHistory> {

    /**
     * 全チャンネルの通知履歴を新しい順に取得する。
     *
     * @param pageable ページ指定
     * @return 通知時刻の降順に並んだ履歴
     */
    Page<NotificationHistory> findAllByOrderByNotifiedAtDesc(Pageable pageable);

    /**
     * 特定チャンネルの通知履歴を新しい順に取得する。
     *
     * @param channel  対象チャンネル
     * @param pageable ページ指定
     * @return 通知時刻の降順に並んだ履歴
     */
    Page<NotificationHistory> findByChannelOrderByNotifiedAtDesc(MonitoredChannel channel, Pageable pageable);

    /**
     * 指定時刻より後に記録された履歴の件数を数える。
     *
     * @param since 集計の起点となる時刻（この時刻より後が対象）
     * @return 件数
     */
    long countByNotifiedAtAfter(LocalDateTime since);

    /**
     * 指定時刻より後に記録された履歴のうち、特定の結果のものだけを数える。
     *
     * <p>ダッシュボードで「直近の通知がどれだけ失敗しているか」を出すために使う。
     * 通知が失敗し続けていても件数だけ見ていると気づけないため。
     *
     * @param status 数えたい送信結果
     * @param since  集計の起点となる時刻（この時刻より後が対象）
     * @return 件数
     */
    long countByStatusAndNotifiedAtAfter(NotificationHistory.NotificationResultType status, LocalDateTime since);
}
