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
 * ログイン中の利用者が録画に付ける「視聴済み」「お気に入り」と、再生位置を読み書きする。
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
     * 再生位置を保存する。{@code null} なら消す（最後まで見たとき。次に開いたら先頭から始める）。
     *
     * <p>画面は再生中に 15 秒おき、止めたとき、別の録画へ切り替えたとき・閉じたとき、ページを離れるときに呼ぶ。
     * 行は視聴済み・お気に入りと同じく「無ければ作る」。画面は視聴済みの印を送っている最中には呼ばないので、
     * 同じ組の行を 2 つの要求が同時に作ることはふつうは起きない（起きた場合の扱いは視聴済み・お気に入りと同じ）。
     * 既にある行へ書くときは、{@link RecordingMark} の {@code @DynamicUpdate} により位置の 2 列だけを書き戻す
     * （同時に押されたお気に入りなどを古い値で消さない）。
     *
     * @param recordingId     録画の主キー
     * @param positionSeconds 再生位置（秒）。消すなら {@code null}
     * @throws RecordingNotFoundException 録画が存在しないとき
     * @throws IllegalArgumentException   位置が負のとき（400）
     */
    @Transactional
    public void setPosition(Long recordingId, Integer positionSeconds) {
        if (positionSeconds != null && positionSeconds < 0) {
            throw new IllegalArgumentException("再生位置は 0 以上で指定してください");
        }
        update(recordingId, mark -> {
            mark.setPositionSeconds(positionSeconds);
            mark.setPositionUpdatedAt(positionSeconds == null ? null : Instant.now());
        });
    }

    /**
     * ログイン中の利用者がその録画で保存した再生位置を返す。
     *
     * <p>行を作らない（読むだけで行が増えると、開いただけの録画にも印の行ができるため）。
     *
     * @param recordingId 録画の主キー
     * @return 再生位置（秒）。保存していなければ {@code null}
     * @throws RecordingNotFoundException 録画が存在しないとき
     */
    @Transactional(readOnly = true)
    public Integer getPosition(Long recordingId) {
        Recording recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
        return recordingMarkRepository.findByUserAndRecording(currentAppUser.require(), recording)
                .map(RecordingMark::getPositionSeconds)
                .orElse(null);
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
