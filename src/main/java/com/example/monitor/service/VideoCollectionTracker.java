package com.example.monitor.service;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.VideoCollectionState;
import com.example.monitor.repository.VideoCollectionStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * チャンネルごとの動画収集の状態（{@code VideoCollectionState}）を記録し、次に取りに行く範囲を決める。
 * <p>失敗を「新着なし」に見せず、初回取得前も含めて画面へ伝える。
 */
@Service @RequiredArgsConstructor
public class VideoCollectionTracker {
    private final VideoCollectionStateRepository repository;
    private final UptimeTracker uptime;

    /**
     * このチャンネルの動画を集め始めた時刻を返す。
     * <p>初めて呼ばれたときに、アプリの起動時刻とチャンネルの登録時刻の遅い方で状態を作る。それより前の投稿は収集の対象にしないため。
     *
     * @param channel 対象のチャンネル
     * @return 収集を始めた時刻
     */
    public synchronized Instant collectingSince(MonitoredChannel channel) {
        return state(channel).getCollectingSince();
    }

    /**
     * 次の収集で取りに行く投稿の下限の時刻を返す。
     * <p>前回成功した時刻の 1 日前から（まだ成功していなければ収集を始めた時刻から）にし、収集を始めた時刻より前には戻らない。取りこぼしを避けつつ、毎回全投稿を読み直してクォータを使わないため。
     *
     * @param channel 対象のチャンネル
     * @return この時刻より新しい投稿を取る
     */
    public synchronized Instant querySince(MonitoredChannel channel) {
        var state = state(channel);
        Instant overlap = state.getSucceededAt() == null ? state.getCollectingSince()
                : state.getSucceededAt().minus(Duration.ofDays(1));
        return overlap.isAfter(state.getCollectingSince()) ? overlap : state.getCollectingSince();
    }

    /**
     * 収集を試みた結果を記録する。
     * <p>失敗も記録するのは、画面で「新着なし」と「取得できていない」を見分けるため。
     *
     * @param channel 対象のチャンネル
     * @param success 取得できた場合 {@code true}。{@code true} のときだけ最後に成功した時刻を進める
     */
    public synchronized void checked(MonitoredChannel channel, boolean success) {
        var state = state(channel);
        state.setCheckedAt(Instant.now());
        state.setFailed(!success);
        if (success) state.setSucceededAt(state.getCheckedAt());
        repository.save(state);
    }

    /**
     * 画面へ返す取得状態を作る。
     * <p>読むだけで状態は作らない。まだ一度も収集していなければ、時刻は {@code null}・失敗中でない、になる。
     *
     * @param channel 対象のチャンネル
     * @return 最後に確認・成功した時刻と、失敗中か
     */
    public Snapshot snapshot(MonitoredChannel channel) {
        var state = repository.findById(channel.getId()).orElse(null);
        return new Snapshot(channel.getId(), channel.getChannelName(), state == null ? null : state.getCheckedAt(),
                state == null ? null : state.getSucceededAt(), state != null && state.isFailed());
    }

    private VideoCollectionState state(MonitoredChannel channel) {
        return repository.findById(channel.getId()).orElseGet(() -> {
            var state = new VideoCollectionState();
            state.setId(channel.getId());
            state.setChannel(channel);
            Instant start = uptime.getStartedAt().atZone(ZoneId.systemDefault()).toInstant();
            if (channel.getCreatedAt() != null) {
                Instant created = channel.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
                if (created.isAfter(start)) start = created;
            }
            state.setCollectingSince(start);
            return repository.save(state);
        });
    }

    /** 例外本文や認証情報を公開せず、利用者に必要な取得状態だけ返す。 */
    public record Snapshot(Long id, String name, Instant checkedAt, Instant succeededAt, boolean error) {}
}
