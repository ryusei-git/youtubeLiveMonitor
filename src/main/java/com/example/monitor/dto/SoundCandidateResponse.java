package com.example.monitor.dto;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.SoundCandidate;

/**
 * 検出器が録画に付けた候補 1 件（Issue #470）。
 *
 * <p>答えた人は返さず、最後に答えたのがログイン中の利用者か（{@code reviewedByMe}）だけを返す。
 * 画面が要るのは「自分の答えなら取り消せる」の判定だけのため（{@link SoundMarkResponse} の {@code mine} と同じ考え方）。
 *
 * @param id           候補の主キー（答えに使う）
 * @param positionMs   録画の先頭からの位置（ミリ秒）
 * @param score        検出器の点数（耳キスらしさの確率。0〜1）
 * @param verdict      答え。{@code null} ならまだ誰も答えていない
 * @param reviewedByMe 最後に答えたのがログイン中の利用者か
 */
public record SoundCandidateResponse(Long id, long positionMs, double score, SoundCandidate.Verdict verdict,
                                     boolean reviewedByMe) {

    /**
     * 候補のエンティティから作る。
     *
     * @param candidate 候補
     * @param current   ログイン中の利用者
     * @return レスポンス
     */
    public static SoundCandidateResponse from(SoundCandidate candidate, AppUser current) {
        AppUser reviewer = candidate.getReviewedBy();
        return new SoundCandidateResponse(candidate.getId(), candidate.getPositionMs(), candidate.getScore(),
                candidate.getVerdict(), reviewer != null && reviewer.getId().equals(current.getId()));
    }
}
