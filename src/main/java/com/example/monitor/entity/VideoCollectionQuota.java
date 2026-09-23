package com.example.monitor.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

/** 再起動しても収集用の上限をリセットせず、通知に使うクォータを残す。 */
@Entity @Table(name = "video_collection_quota") @Getter @Setter @NoArgsConstructor
public class VideoCollectionQuota {
    @Id private String id;
    private LocalDate quotaDate;
    private int requests;
}
