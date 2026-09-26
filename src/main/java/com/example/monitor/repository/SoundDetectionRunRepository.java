package com.example.monitor.repository;

import com.example.monitor.entity.Recording;
import com.example.monitor.entity.SoundDetectionRun;
import com.example.monitor.entity.SoundMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 検出の実行記録（{@link SoundDetectionRun}）の永続化を担当するリポジトリ。 */
public interface SoundDetectionRunRepository extends JpaRepository<SoundDetectionRun, Long> {

    /**
     * 録画・種類・版の組の実行記録を引く。一意制約があるので、あっても 1 つ。
     *
     * @param recording       録画
     * @param kind            種類
     * @param detectorVersion 検出器の版
     * @return 実行記録。まだ一度も始めていなければ空
     */
    Optional<SoundDetectionRun> findByRecordingAndKindAndDetectorVersion(
            Recording recording, SoundMark.Kind kind, String detectorVersion);

    /**
     * 見回りで検出する録画を、新しい順に引く。
     *
     * <p>対象は、再生できる録画（完了・途中まで）で、長さが分かっていて、その版の実行記録が
     * 「完了」でも「失敗が上限の回数」でもないもの。長さの分からない録画を外すのは、検出器の持つメモリが
     * 録画の長さに比例し、長すぎる録画を避けられないため（{@link Recording#durationSeconds} は
     * 録画の後始末が後から埋めるので、埋まれば対象になる）。ファイルの有無は DB では分からないので、呼び出し側で確かめる。
     *
     * <p>録画を返すが、条件の中心が実行記録なのでこちらに置く。
     *
     * @param kind            種類
     * @param detectorVersion 検出器の版
     * @param maxAttempts     失敗がこの回数に達した録画は外す
     * @return 対象の録画（開始の新しい順）
     */
    @Query("""
            SELECT r FROM Recording r
             WHERE r.status IN (
                    com.example.monitor.entity.Recording.RecordingStatus.COMPLETED,
                    com.example.monitor.entity.Recording.RecordingStatus.PARTIAL)
               AND r.durationSeconds IS NOT NULL
               AND NOT EXISTS (
                    SELECT d.id FROM SoundDetectionRun d
                     WHERE d.recording = r AND d.kind = :kind AND d.detectorVersion = :detectorVersion
                       AND (d.status = com.example.monitor.entity.SoundDetectionRun.Status.DONE
                            OR d.attempts >= :maxAttempts))
             ORDER BY r.startedAt DESC, r.id DESC
            """)
    List<Recording> findPendingRecordings(@Param("kind") SoundMark.Kind kind,
                                          @Param("detectorVersion") String detectorVersion,
                                          @Param("maxAttempts") int maxAttempts);
}
