package com.example.monitor.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

/**
 * チャンネルごとの動画収集の状態（収集を始めた時刻・最後に確認・成功した時刻・失敗中か）。
 *
 * <p>初回収集の境界を永続化し、再起動直前に公開された動画も次回の取得で拾う。
 */
@Entity @Table(name = "video_collection_states")
@Getter @Setter @NoArgsConstructor
public class VideoCollectionState {
    @Id private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MonitoredChannel channel;
    private Instant collectingSince;
    private Instant checkedAt;
    private Instant succeededAt;
    @Column(columnDefinition = "boolean default false")
    private boolean failed;
}
