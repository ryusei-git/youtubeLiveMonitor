package com.example.monitor.service;

import com.example.monitor.dto.SoundMarkRequest;
import com.example.monitor.dto.SoundMarkResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.SoundMark;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.exception.SoundMarkNotFoundException;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.SoundMarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 録画に付ける音の印（{@link SoundMark}）を読み書きする。
 *
 * <p>印を付ける・消す利用者は常にログイン中の本人で、引数では受け取らない
 * （受け取る形にすると、渡された ID の検証漏れがそのまま他人の印の削除になる。{@link RecordingMarkService} と同じ）。
 *
 * <p>監査ログには残さない。印そのものが付けた人と時刻を持ち、消せるのも本人だけなので、
 * 証跡を別に持つ必要が無いため。
 */
@Service
@RequiredArgsConstructor
public class SoundMarkService {

    /**
     * 同じ人が同じ種類の印を付け直したとき、二度押しとしてまとめる幅（前後それぞれ、ミリ秒）。
     *
     * <p>耳キスは一瞬の音で、1 秒以内に同じ人が付け直すのは押し直しとみなせるため。
     * ほかの人が近くに付けた印はまとめない。学び直しで、人ごとの付け方の一致を見られるようにするため。
     */
    private static final long DOUBLE_PRESS_WINDOW_MS = 1000;

    /**
     * 録画の長さ（{@link Recording#durationSeconds}）を超えて付けられる余裕（ミリ秒）。
     *
     * <p>長さは秒未満を切り捨てた値で、再生位置は最後の 1 秒の途中まで進むため。
     */
    private static final long DURATION_MARGIN_MS = 1000;

    private final SoundMarkRepository soundMarkRepository;
    private final RecordingRepository recordingRepository;
    private final CurrentAppUser currentAppUser;

    /**
     * 録画に付いた、ある種類の印を位置の順に返す。ほかの人の印も含む。
     *
     * @param recordingId 録画の主キー
     * @param kind        種類
     * @return 印の一覧
     * @throws IllegalArgumentException   種類が無い・知らない種類のとき（400）
     * @throws RecordingNotFoundException 録画が存在しないとき（404）
     */
    @Transactional(readOnly = true)
    public List<SoundMarkResponse> list(Long recordingId, String kind) {
        SoundMark.Kind parsedKind = parseKind(kind);
        Recording recording = requireRecording(recordingId);
        AppUser user = currentAppUser.require();
        return soundMarkRepository.findByRecordingAndKindOrderByPositionMsAscIdAsc(recording, parsedKind).stream()
                .map(mark -> SoundMarkResponse.from(mark, user))
                .toList();
    }

    /**
     * 印を付ける。同じ人が同じ種類の印を前後 1 秒以内に付けていれば、新しく作らずにそれを返す（二度押し対策）。
     *
     * @param recordingId 録画の主キー
     * @param request     種類と位置
     * @return 作った印。二度押しなら前の印
     * @throws IllegalArgumentException   種類が不正、位置が無い・負・録画の長さを超えるとき（400）
     * @throws RecordingNotFoundException 録画が存在しないとき（404）
     */
    @Transactional
    public SoundMarkResponse add(Long recordingId, SoundMarkRequest request) {
        SoundMark.Kind kind = parseKind(request.kind());
        Recording recording = requireRecording(recordingId);
        long positionMs = requireValidPosition(request.positionMs(), recording);
        AppUser user = currentAppUser.require();
        // ponytail: 確かめてから保存するので、同時に届いた 2 つの要求は両方とも作りうる。
        // 画面が送信中にボタンを止めれば足りる。それでも重なるなら、利用者の行をロックしてから確かめる
        SoundMark mark = soundMarkRepository.findFirstByRecordingAndUserAndKindAndPositionMsBetween(
                        recording, user, kind, positionMs - DOUBLE_PRESS_WINDOW_MS, positionMs + DOUBLE_PRESS_WINDOW_MS)
                .orElseGet(() -> soundMarkRepository.save(new SoundMark(recording, user, kind, positionMs)));
        return SoundMarkResponse.from(mark, user);
    }

    /**
     * 印を消す。消せるのは、その録画に本人が付けた印だけ。
     *
     * @param recordingId 録画の主キー
     * @param markId      印の主キー
     * @throws RecordingNotFoundException 録画が存在しないとき（404）
     * @throws SoundMarkNotFoundException 印が無い・別の録画の印・他人の印のとき（404）
     */
    @Transactional
    public void delete(Long recordingId, Long markId) {
        Recording recording = requireRecording(recordingId);
        AppUser user = currentAppUser.require();
        if (soundMarkRepository.deleteByIdAndRecordingAndUser(markId, recording, user) == 0) {
            throw new SoundMarkNotFoundException(markId);
        }
    }

    /**
     * 種類の文字列を enum に直す。
     *
     * <p>{@code Kind.valueOf} を使わないのは、知らない名前のときの文言にクラス名が入り、
     * {@code null} では 400 ではなく 500 になるため。
     */
    private static SoundMark.Kind parseKind(String kind) {
        return Arrays.stream(SoundMark.Kind.values())
                .filter(candidate -> candidate.name().equals(kind))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("印の種類が正しくありません: " + kind));
    }

    private Recording requireRecording(Long recordingId) {
        return recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
    }

    /**
     * 位置を確かめる。録画の長さが分からない（録画中・読み取れなかった）ときは上限を見ない。
     */
    private static long requireValidPosition(Long positionMs, Recording recording) {
        if (positionMs == null || positionMs < 0) {
            throw new IllegalArgumentException("印の位置（positionMs）は 0 以上で指定してください");
        }
        Integer durationSeconds = recording.getDurationSeconds();
        if (durationSeconds != null && positionMs > durationSeconds * 1000L + DURATION_MARGIN_MS) {
            throw new IllegalArgumentException("印の位置（positionMs）が録画の長さを超えています");
        }
        return positionMs;
    }
}
