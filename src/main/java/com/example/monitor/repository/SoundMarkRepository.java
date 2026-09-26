package com.example.monitor.repository;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.SoundMark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 音の印（{@link SoundMark}）の永続化を担当するリポジトリ。 */
public interface SoundMarkRepository extends JpaRepository<SoundMark, Long> {

    /**
     * 録画に付いた、ある種類の印を位置の順に引く。全員の印を含む。
     *
     * <p>同じ位置の印は付けた順に並べる（並びが要求ごとに変わらないように）。
     *
     * @param recording 録画
     * @param kind      種類
     * @return 印の一覧
     */
    List<SoundMark> findByRecordingAndKindOrderByPositionMsAscIdAsc(Recording recording, SoundMark.Kind kind);

    /**
     * 利用者が録画のある範囲に付けた、ある種類の印を 1 つ引く。二度押しを見つけるためのもの。
     *
     * @param recording 録画
     * @param user      利用者
     * @param kind      種類
     * @param fromMs    範囲の始まり（ミリ秒、含む）
     * @param toMs      範囲の終わり（ミリ秒、含む）
     * @return 範囲内の印。複数あればどれか 1 つ
     */
    Optional<SoundMark> findFirstByRecordingAndUserAndKindAndPositionMsBetween(
            Recording recording, AppUser user, SoundMark.Kind kind, long fromMs, long toMs);

    /**
     * 利用者が録画に付けた印を 1 つ消す。
     *
     * @param id        印の主キー
     * @param recording 録画
     * @param user      利用者
     * @return 消した件数。別の録画の印・他人の印・無い印なら 0
     */
    long deleteByIdAndRecordingAndUser(Long id, Recording recording, AppUser user);
}
