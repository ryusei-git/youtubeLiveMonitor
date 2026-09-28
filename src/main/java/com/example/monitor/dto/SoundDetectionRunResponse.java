package com.example.monitor.dto;

import com.example.monitor.entity.SoundDetectionRun;

import java.time.Instant;

/**
 * 録画 1 本の、今の版の検出の実行記録（Issue #470）。管理者が今すぐ検出の結果を確かめるのに使う。
 *
 * <p>エンティティを直接返さないのは、録画への遅延読み込みの参照を持つため
 * （{@code docs/pitfalls.md}「エンティティを API に直接返さない」）。
 *
 * @param status          結果（{@code DONE}・{@code FAILED}）。検出の途中も {@code FAILED}（理由は「実行中に止まった」）。
 *                        アプリの終了で止めた回は、次の起動の後に理由が「アプリの終了で止めた（回数に数えない）」になる
 * @param candidateCount  検出器が出した候補の数
 * @param attempts        最後に完了してから検出を始めた回数（アプリの終了で止めた回は数えない）
 * @param message         失敗の理由（ffmpeg が失敗したときは ffmpeg の出力の末尾を含む。500 文字まで）。完了なら {@code null}
 * @param startedAt       最後に検出を始めた時刻
 * @param finishedAt      最後の検出が終わった時刻。検出の途中・途中で止まったときは {@code null}
 * @param detectorVersion 検出器の版
 */
public record SoundDetectionRunResponse(SoundDetectionRun.Status status, int candidateCount, int attempts,
                                        String message, Instant startedAt, Instant finishedAt,
                                        String detectorVersion) {

    /**
     * 実行記録のエンティティから作る。
     *
     * @param run 実行記録
     * @return レスポンス
     */
    public static SoundDetectionRunResponse from(SoundDetectionRun run) {
        return new SoundDetectionRunResponse(run.getStatus(), run.getCandidateCount(), run.getAttempts(),
                run.getMessage(), run.getStartedAt(), run.getFinishedAt(), run.getDetectorVersion());
    }
}
