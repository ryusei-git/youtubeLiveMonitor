package com.example.monitor.service;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * その配信を録画すべきかを、チャンネル単位の設定と購読ごとの設定から合成して決める。
 *
 * <h2>「誰か 1 人でも希望していれば録画する」</h2>
 * 同じチャンネルを複数人が購読できるため、録画の希望をチャンネル側の 1 つの値で共有すると
 * <b>誰かが切った瞬間に他の人の録画も止まる</b>。そこで希望は購読ごとに持ち、
 * ここで論理和にまとめる。管理者がチャンネル単位で設定した希望
 * （{@code MonitoredChannel.recordEnabled}）も対等な 1 票として扱う。
 *
 * <h2>条件キーワードは希望した人ごとに見る</h2>
 * 「A さんは ASMR だけ、B さんは全部」という状態がありうるので、
 * <b>キーワードの判定は希望者ごとに行う</b>。キーワードを寄せ集めて 1 本にすると、
 * A さんの条件で B さんの録画まで絞られてしまう。
 *
 * <h2>{@code LiveStreamPollingScheduler} から切り出している理由</h2>
 * 巡回の本体に購読テーブルの参照を持ち込むと、巡回・録画・通知という
 * 一番壊したくない処理が購読の都合で膨らんでいく。判定だけをここに閉じ込めている。
 */
@Service
@RequiredArgsConstructor
public class RecordingIntentResolver {

    private final UserSubscriptionRepository userSubscriptionRepository;

    /**
     * 録画すべきかどうかの判定結果。
     *
     * <p>「そもそも誰も希望していない」と「希望者はいるが条件に合わない」を分けているのは、
     * ログに出す見送りの理由を書き分けるため（「誰も録画を希望していない」のか「希望者はいるが条件に合わない」のか）。
     *
     * @param anyoneEnabled 希望している人が 1 人でもいるか
     * @param matched       希望者のうち、この配信が条件に合う人がいるか
     */
    public record RecordingIntent(boolean anyoneEnabled, boolean matched) {
    }

    /**
     * この配信を録画すべきかを判定する。
     *
     * @param channel  対象のチャンネル
     * @param title    配信タイトル。取得できていなければ {@code null}
     * @param category 配信カテゴリ。プラットフォームに無ければ {@code null}
     * @return 判定結果
     */
    @Transactional(readOnly = true)
    public RecordingIntent resolve(MonitoredChannel channel, String title, String category) {
        boolean channelEnabled = channel.isRecordEnabled();
        if (channelEnabled && channel.matchesFilter(title, category)) {
            // 条件に合う希望者が見つかった時点で購読を読みに行かずに済ませる
            return new RecordingIntent(true, true);
        }

        List<UserSubscription> wanting = userSubscriptionRepository.findByChannelAndRecordEnabledTrue(channel);
        boolean anyoneEnabled = channelEnabled || !wanting.isEmpty();
        boolean matched = wanting.stream().anyMatch(s -> s.matchesFilter(title, category));
        return new RecordingIntent(anyoneEnabled, matched);
    }
}
