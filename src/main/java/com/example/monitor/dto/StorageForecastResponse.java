package com.example.monitor.dto;

import java.time.LocalDateTime;

/**
 * 録画フォルダーの空きが減って、新しい録画が始まらなくなるまでの見込み。
 *
 * <p>空きが 0 になる時ではなく、録画を始めるしきい値（{@code monitor.recording.min-free-gb}）を割る時を見込む。
 * 利用者が知りたいのは「いつ録画が止まるか」であり、そこから先は空きが残っていても録れないため。
 *
 * @param freeBytes        録画フォルダーがあるボリュームの空き容量（バイト）。読めなければ {@code null}
 * @param reserveBytes     録画を始めるしきい値（バイト）。これを割ると新しい録画を始めない
 * @param dailyGrowthBytes 1 日あたりの増え方（バイト）
 * @param windowDays       増え方を測った日数（最大 14）
 * @param hoursUntilFull   しきい値を割るまでの時間。増えていない・読めないときは {@code null}、すでに割っていれば 0
 * @param fullAt           しきい値を割る見込みの日時。{@code hoursUntilFull} が 0 か {@code null} のときは {@code null}
 * @param status           {@link Status}
 */
public record StorageForecastResponse(Long freeBytes, long reserveBytes, long dailyGrowthBytes, int windowDays,
                                      Long hoursUntilFull, LocalDateTime fullAt, Status status) {

    /** 見込みの状態。画面の出し分けに使う。 */
    public enum Status {
        /** 7 日以上もつ。 */
        OK,
        /** 7 日未満で録画が止まる。 */
        WARNING,
        /** すでにしきい値を割っていて、新しい録画を始めていない。 */
        STOPPED,
        /** 測った期間に録画が増えていないので見込みを出せない。 */
        NO_GROWTH,
        /**
         * 空き容量を読めなかった。「判定できなかった」を「止まっている」や「余裕がある」と
         * 取り違えないよう別にしている（{@code docs/pitfalls.md}）。
         */
        UNKNOWN
    }
}
