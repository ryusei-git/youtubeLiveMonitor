package com.example.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * {@link com.example.monitor.service.StreamRecorder} が開始した録画 1 件の記録。
 *
 * <p>{@link MonitoredChannel#lastRecordedVideoId} は「直近の録画対象」しか
 * 保持しないため、過去に録画した動画の一覧や再生画面を作るには別途履歴が要る。
 * {@link com.example.monitor.entity.NotificationHistory} と同じ考え方で、録画の
 * 開始・完了・失敗をそれぞれ 1 件のレコードとして残す。
 *
 * <p>{@link #filePath} は録画開始時点で確定させている。{@code StreamRecorder} が
 * {@code yt-dlp} に {@code --merge-output-format mp4} を明示指定しており、最終的な
 * 出力ファイル名（拡張子を含む）が録画開始前から一意に決まるため。
 */
@Entity
@Table(name = "recordings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Recording {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 録画対象のチャンネル。監視対象として登録されていないチャンネルの動画では {@code null} になる。
     *
     * <p>{@link OnDelete} により、チャンネルを削除すると紐づく録画履歴も DB 側で連鎖削除される
     * （{@link NotificationHistory}と同じ理由）。実ファイルはこの連鎖削除では消えないため、
     * 別途 {@code recordings/} ディレクトリの整理は利用者側で行う必要がある。
     *
     * <p><b>{@code null} を許す理由。</b>URL を指定したダウンロード
     * （{@link com.example.monitor.service.VideoDownloadService}）では、対象が
     * 監視登録していないチャンネルの動画であることが普通にある。そこで
     * 「登録済みチャンネルの動画しかダウンロードできない」制約を課すのは本末転倒で、
     * かといって<b>紐づけるためだけにチャンネルを勝手に監視対象へ登録するのは論外</b>
     * （利用者が意図しない監視・通知・自動録画が始まってしまう）。
     * 紐づかないものは紐づかないまま記録する。
     *
     * <p>{@code null} を取りうるため、参照する側は必ず未設定の場合を考えること
     * （画面に出す形は {@link com.example.monitor.dto.RecordingResponse#from} が吸収している）。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MonitoredChannel channel;

    /** 録画対象の配信の動画 ID。 */
    @Column(nullable = false)
    private String videoId;

    /** 録画開始時点での配信タイトル。 */
    private String videoTitle;

    /**
     * タイトルの最初の {@code 【】} の中身（{@link com.example.monitor.util.TitleGenreExtractor} 参照）。
     * {@code 【】} が無ければ {@code null}。
     *
     * <p><b>ダッシュボードと違って DB に持つ理由。</b>アーカイブ一覧はジャンルで絞り込み、
     * ジャンルごとの件数も数える。タイトルから毎回求める方式では、数千件を読み込んで
     * アプリ側で絞るしかなく、ページングも件数も DB に任せられない。
     * 既存の行は {@link com.example.monitor.service.RecordingGenreBackfiller} が起動時に埋める。
     */
    @Column(length = 200)
    private String genre;

    /**
     * 録画ファイルの保存先パス（{@code monitor.recording.directory} からの相対パス、
     * 例: {@code UCxxxxxxxx/videoId.mp4}）。再生用の静的リソース配信 URL の組み立てに使う。
     */
    @Column(nullable = false)
    private String filePath;

    /** 録画ファイルのサイズ（バイト）。録画完了時に設定される。録画中・失敗時は {@code null}。 */
    private Long fileSizeBytes;

    /**
     * 録画の再生時間（秒）。完成ファイルから {@code ffprobe} で読み取る。
     * 読み取れなかった場合や録画中・失敗時は {@code null}。
     */
    private Integer durationSeconds;

    /**
     * 一覧に出すサムネイル画像のパス（{@code monitor.recording.directory} からの相対パス）。
     * まだ生成していない、または生成に失敗した場合は {@code null}。
     *
     * <p>画像は録画ファイルから {@code ffmpeg} で切り出す。無くても再生はできるので、
     * 生成できなかったときは {@code null} のままにして一覧ではプレースホルダを出す。
     */
    private String thumbnailPath;

    /**
     * 録画の状態。
     *
     * <p><b>{@code columnDefinition} で型を明示しているのは、H2 のネイティブ ENUM 型を
     * 使わせないため。</b>何も指定しないと Hibernate は列を
     * {@code ENUM('COMPLETED','FAILED','RECORDING')} として作る。この型は<b>テーブル作成時の
     * 値だけを許す</b>ため、後から列挙子を増やすと {@code ddl-auto: update} では型が更新されず、
     * 「Value not permitted for column」で全ての更新が失敗するようになる
     * （{@code PARTIAL} を追加した際に実際に発生した）。
     * 単なる文字列として持たせておけば、列挙子を増やしても DB 側の変更が要らない。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private RecordingStatus status;

    /** 録画を開始した時刻。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime startedAt;

    /** 録画が完了・失敗した時刻。録画中は {@code null}。 */
    private LocalDateTime completedAt;

    /** 開始時刻を自動設定する。JPA が INSERT 直前に呼び出す。 */
    @PrePersist
    void applyStartedAtOnInsert() {
        this.startedAt = LocalDateTime.now();
    }

    /** 録画の状態。 */
    public enum RecordingStatus {
        /** 録画プロセスが実行中。 */
        RECORDING,
        /** 録画が正常に完了し、再生可能なファイルが存在する。 */
        COMPLETED,
        /**
         * 配信の途中で録画が止まったが、そこまでの内容は再生できる状態に直してある。
         *
         * <p><b>{@link #COMPLETED} と区別する理由。</b>途中で切れた録画を「完了」と記録すると、
         * 3時間の配信が40分で終わっていても画面上は正常に見え、なぜ短いのか分からなくなる。
         * 再生できる点は同じなので一覧にも出すし再生もできるが、完全ではないことは明示する。
         *
         * <p>この状態になるのは {@link com.example.monitor.service.RecordingSalvager} が
         * 詰め替えに成功した場合だけで、再生できないものは {@link #FAILED} のままになる。
         */
        PARTIAL,
        /**
         * 録画プロセスが異常終了し、再生できるファイルを用意できなかった。
         *
         * <p>途中まででもファイルが残っていれば {@link #PARTIAL} になるため、
         * この状態は「そもそも中身が無い」ことを意味する。
         */
        FAILED
    }
}
