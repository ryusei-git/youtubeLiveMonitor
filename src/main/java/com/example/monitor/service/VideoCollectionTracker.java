package com.example.monitor.service;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.VideoCollectionState;
import com.example.monitor.repository.VideoCollectionStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 失敗を「新着なし」に見せず、初回取得前も含めて画面へ伝える。 */
@Service @RequiredArgsConstructor
public class VideoCollectionTracker {
    private final VideoCollectionStateRepository repository;
    private final UptimeTracker uptime;

    public synchronized Instant collectingSince(MonitoredChannel channel) {
        return state(channel).getCollectingSince();
    }

    /** 再取得は一日分を重ね、毎回過去の全投稿を読み直してクォータを消費しない。 */
    public synchronized Instant querySince(MonitoredChannel channel) {
        var state = state(channel);
        Instant overlap = state.getSucceededAt() == null ? state.getCollectingSince()
                : state.getSucceededAt().minus(Duration.ofDays(1));
        return overlap.isAfter(state.getCollectingSince()) ? overlap : state.getCollectingSince();
    }

    public synchronized void checked(MonitoredChannel channel, boolean success) {
        var state = state(channel);
        state.setCheckedAt(Instant.now());
        state.setFailed(!success);
        if (success) state.setSucceededAt(state.getCheckedAt());
        repository.save(state);
    }

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
