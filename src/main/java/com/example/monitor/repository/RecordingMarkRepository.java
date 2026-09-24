package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.RecordingMark;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
