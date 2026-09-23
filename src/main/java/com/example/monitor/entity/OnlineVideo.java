package com.example.monitor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

/** 録画の有無に依存せず視聴先を残す。動画本体は保存しない。 */
@Entity
@Table(name = "online_videos", indexes = @Index(columnList = "published_at"))
@Getter @Setter @NoArgsConstructor
public class OnlineVideo {
    @Id @Column(length = 100)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MonitoredChannel channel;
    @Column(length = 2048, nullable = false)
    private String watchUrl;
    @Column(length = 2048)
    private String liveWatchUrl;
    @Column(length = 2048)
    private String thumbnailUrl;
    @Column(length = 1024, nullable = false)
    private String title;
    private Instant publishedAt;
    private Instant discoveredAt;
    private Instant lastObservedAt;
    @Column(columnDefinition = "boolean default false")
    private boolean live;
}
