package com.example.monitor.dto;

import java.time.LocalDateTime;

/**
 * 1 分ごとに記録するリソースの主な値。
 *
 * <p>{@link ResourceSnapshotResponse} を丸ごと持たないのは、24 時間分（1440 件）を
 * メモリに置くため、折れ線に使う値だけに絞って軽くしておくため。
 *
 * @param at                           記録した時刻
 * @param systemCpuPercent             端末全体の CPU 使用率。前回値が無い最初の記録は {@code null}
 * @param systemMemoryUsedPercent      端末全体のメモリ使用率
 * @param serviceCpuPercent            このサービスの CPU 使用率（端末全体を 100% とした値）
 * @param serviceMemoryBytes           このサービスの実メモリ
 * @param recorderCount                録画プロセスの数
 * @param networkReceiveBytesPerSecond 受信量（毎秒）。前回値が無い最初の記録は {@code null}
 * @param networkSendBytesPerSecond    送信量（毎秒）。前回値が無い最初の記録は {@code null}
 * @param diskFreeBytes                録画の保存先ボリュームの空き。取得できなければ {@code null}
 * @param recordingsBytes              録画フォルダーの実ファイルの合計
 * @param registeredCpuPercent         登録したサービス全体の CPU 使用率（端末全体を 100% とした値）。
 *                                     分からないプロセスが 1 つでもあれば {@code null}
 * @param registeredMemoryBytes        登録したサービス全体の実メモリの合計
 */
public record ResourceHistoryPoint(
        LocalDateTime at,
        Double systemCpuPercent,
        double systemMemoryUsedPercent,
        Double serviceCpuPercent,
        long serviceMemoryBytes,
        int recorderCount,
        Long networkReceiveBytesPerSecond,
        Long networkSendBytesPerSecond,
        Long diskFreeBytes,
        long recordingsBytes,
        Double registeredCpuPercent,
        long registeredMemoryBytes
) {}
