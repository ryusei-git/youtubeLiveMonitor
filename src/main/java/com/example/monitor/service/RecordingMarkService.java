package com.example.monitor.service;

import com.example.monitor.dto.RecordingMarkResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.RecordingMark;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.repository.RecordingMarkRepository;
import com.example.monitor.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * ログイン中の利用者が録画に付ける「視聴済み」「お気に入り」を読み書きする。
 *
 * <p>対象の利用者は常にログイン中の本人で、引数では受け取らない
 * （受け取る形にすると、渡された ID の検証漏れがそのまま他人の印の書き換えになる）。
 *
 * <p>監査ログには残さない。利用者個人の閲覧の記録であり、状態変更操作の証跡の対象外のため。
 */
@Service
@RequiredArgsConstructor
public class RecordingMarkService {

    private final RecordingMarkRepository recordingMarkRepository;
    private final RecordingRepository recordingRepository;
    private final CurrentAppUser currentAppUser;

    /**
     * 視聴済みを切り替える。
     *
     * @param recordingId 録画の主キー
     * @param watched     視聴済みにするなら {@code true}
     * @return 変更後の印
     * @throws RecordingNotFoundException 録画が存在しないとき
     */
    @Transactional
    public RecordingMarkResponse setWatched(Long recordingId, boolean watched) {
        return update(recordingId, mark -> mark.setWatchedAt(watched ? Instant.now() : null));
    }

    /**
     * お気に入りを切り替える。
     *
     * @param recordingId 録画の主キー
     * @param favorite    お気に入りにするなら {@code true}
     * @return 変更後の印
     * @throws RecordingNotFoundException 録画が存在しないとき
     */
    @Transactional
    public RecordingMarkResponse setFavorite(Long recordingId, boolean favorite) {
        return update(recordingId, mark -> mark.setFavorite(favorite));
    }

    /**
     * 印の行を「無ければ作る」うえで変更を当てる。
     *
     * <p>印の行は最初に印を付けたときに初めて作る。録画の作成時に全利用者分を作ると、
     * 録画の保存経路（監視ループ側）にまで印の都合を持ち込むことになるため。
     */
    private RecordingMarkResponse update(Long recordingId, Consumer<RecordingMark> change) {
        Recording recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
        AppUser user = currentAppUser.require();
        RecordingMark mark = recordingMarkRepository.findByUserAndRecording(user, recording)
                .orElseGet(() -> new RecordingMark(user, recording));
        change.accept(mark);
        return RecordingMarkResponse.from(recordingMarkRepository.save(mark));
    }
}
