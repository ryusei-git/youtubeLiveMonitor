package com.example.monitor.dto;

import com.example.monitor.entity.RecordingMark;

/**
 * ログイン中の利用者がその録画に付けている印。
 *
 * <p>視聴済みは時刻ではなく真偽値で返す。画面が要るのは「付いているか」だけで、
 * 時刻を返すと画面側に {@code null} 判定を散らばらせることになるため。
 *
 * @param recordingId 録画の主キー
 * @param watched     視聴済みか
 * @param favorite    お気に入りか
 */
public record RecordingMarkResponse(Long recordingId, boolean watched, boolean favorite) {

    /**
     * 印のエンティティから作る。
     *
     * @param mark 印
     * @return レスポンス
     */
    public static RecordingMarkResponse from(RecordingMark mark) {
        return new RecordingMarkResponse(
                mark.getRecording().getId(), mark.getWatchedAt() != null, mark.isFavorite());
    }
}
