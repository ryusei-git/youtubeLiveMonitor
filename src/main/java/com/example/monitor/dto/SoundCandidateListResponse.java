package com.example.monitor.dto;

import java.util.List;

/**
 * 録画に付いた候補の一覧と、今の版の検出が済んだか（Issue #470）。
 *
 * <p>状態（{@code state}）を候補と一緒に返すのは、候補が 0 件のときに「まだ検出していない」
 * 「検出して 0 件だった」「検出に失敗した」「長すぎて検出しない」を画面が見分けられるようにするため。
 *
 * @param state           今の版の検出の状態
 * @param detectorVersion 今の検出器の版。候補はこの版のものだけ
 * @param candidates      候補（位置の順）
 */
public record SoundCandidateListResponse(State state, String detectorVersion,
                                         List<SoundCandidateResponse> candidates) {

    /** 今の版の検出の状態。 */
    public enum State {
        /** まだ検出していない（検出の途中を含む）、または失敗したが上限の回数に達しておらず、見回りがまた試す。 */
        PENDING,
        /** 検出が済んだ。 */
        DONE,
        /** 失敗が上限の回数に達し、見回りはもう試さない（長すぎる録画は {@link #UNSUPPORTED}）。 */
        FAILED,
        /**
         * 録画が長すぎて（6 時間を超える）、どの版でも検出しない。見回りは実行記録を「失敗（回数は上限）」にして外すが、
         * やり直しても結果は変わらないので、画面には失敗（{@link #FAILED}）と分けて出す。
         */
        UNSUPPORTED
    }
}
