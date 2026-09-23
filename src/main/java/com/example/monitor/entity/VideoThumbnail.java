package com.example.monitor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/** 一覧取得で画像本体まで読み込まないよう、表示用メタデータと分離する。 */
@Entity @Table(name = "video_thumbnails")
@Getter @Setter @NoArgsConstructor
public class VideoThumbnail {
    @Id @Column(length = 100)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "id", insertable = false, updatable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private OnlineVideo video;
    private String contentType;
    @Lob
    private byte[] content;
}
