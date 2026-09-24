package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.RecordingMark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 録画の印（{@link RecordingMark}）の永続化を担当するリポジトリ。 */
public interface RecordingMarkRepository extends JpaRepository<RecordingMark, Long> {

    /**
     * 利用者と録画の組の印を引く。
     *
     * @param user      利用者
     * @param recording 録画
     * @return 印。まだ付けていなければ {@link Optional#empty()}
     */
    Optional<RecordingMark> findByUserAndRecording(AppUser user, Recording recording);

    /**
     * 利用者が指定した録画群に付けた印をまとめて引く。
     *
     * <p>一覧の 1 ページ分を 1 回のクエリで取るためのもの。行ごとに
     * {@link #findByUserAndRecording} を呼ぶと、件数ぶんクエリが飛ぶ。
     *
     * @param userId       利用者の主キー
     * @param recordingIds 録画の主キーの一覧
     * @return 印の一覧。印の無い録画の分は含まない
     */
    List<RecordingMark> findByUser_IdAndRecording_IdIn(Long userId, Collection<Long> recordingIds);
}
