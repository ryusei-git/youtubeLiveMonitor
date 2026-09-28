package com.example.monitor.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 端末全体とこのサービスが今使っているリソース。
 *
 * <p>CPU 使用率はどれも<b>端末全体を 100% とした値</b>にそろえる（8 コアで 1 コアを使い切ったら 12.5%）。
 * プロセスごとの値を 1 コア基準のまま返すと、端末全体の値と足し引きできず、
 * 「このサービスが端末のどれだけを占めているか」が読めなくなるため。
 * CPU 使用率は前回の 1 分ごとの記録との差から出すので、起動直後で前回値が無いときは {@code null}。
 *
 * @param measuredAt 計測した時刻
 * @param system     端末全体
 * @param service    このサービス（アプリ本体・録画プロセス・アプリが起動したその他の外部プロセス）
 * @param warnings   目安を超えている項目。無ければ空
 */
public record ResourceSnapshotResponse(
        LocalDateTime measuredAt,
        SystemUsage system,
        ServiceUsage service,
        List<Warning> warnings
) {

    /**
     * 端末全体のリソース。
     *
     * @param cpuPercent                   CPU 使用率（%）
     * @param cores                        論理コア数
     * @param loadAverage1m                1 分平均負荷。OS が提供しなければ {@code null}
     * @param memoryTotalBytes             メモリの合計
     * @param memoryUsedBytes              使用中のメモリ（合計 − 空き）
     * @param memoryAvailableBytes         空きメモリ（キャッシュなど解放できる分を含む）
     * @param swapTotalBytes               スワップの合計
     * @param swapUsedBytes                スワップの使用量
     * @param diskPath                     録画の保存先（設定値のまま）
     * @param diskTotalBytes               保存先ボリュームの合計。取得できなければ {@code null}
     * @param diskFreeBytes                保存先ボリュームの空き。取得できなければ {@code null}
     * @param networkReceiveBytesPerSecond 受信量（毎秒）
     * @param networkSendBytesPerSecond    送信量（毎秒）
     */
    public record SystemUsage(
            Double cpuPercent, int cores, Double loadAverage1m,
            long memoryTotalBytes, long memoryUsedBytes, long memoryAvailableBytes,
            long swapTotalBytes, long swapUsedBytes,
            String diskPath, Long diskTotalBytes, Long diskFreeBytes,
            Long networkReceiveBytesPerSecond, Long networkSendBytesPerSecond
    ) {}

    /**
     * このサービス全体のリソース。
     *
     * @param cpuPercent  アプリ本体・録画プロセス・その他の外部プロセス（どれも子孫を含む）の CPU 使用率の合計
     * @param memoryBytes アプリ本体・録画プロセス・その他の外部プロセス（どれも子孫を含む）の実メモリの合計
     * @param application アプリ本体（この Java プロセス）
     * @param recorders   録画プロセス
     * @param helpers     アプリが起動した、録画プロセス以外の外部プロセス（アプリの直接の子ごと）
     */
    public record ServiceUsage(
            Double cpuPercent, long memoryBytes,
            ApplicationUsage application, List<RecorderUsage> recorders, List<HelperUsage> helpers
    ) {}

    /**
     * アプリ本体のリソース。
     *
     * @param pid            プロセス ID
     * @param cpuPercent     CPU 使用率
     * @param memoryBytes    実メモリ（RSS）
     * @param heapUsedBytes  ヒープの使用量
     * @param heapMaxBytes   ヒープの上限
     * @param threads        スレッド数（JVM 内部のスレッドを含む OS 上の数）
     */
    public record ApplicationUsage(
            int pid, Double cpuPercent, long memoryBytes,
            long heapUsedBytes, long heapMaxBytes, int threads
    ) {}

    /**
     * 録画プロセス（yt-dlp）1 つ分のリソース。
     *
     * @param pid         プロセス ID
     * @param name        プロセス名
     * @param label       録画中のチャンネル名。引けなければ動画 ID
     * @param cpuPercent  CPU 使用率（子孫は含まない）
     * @param memoryBytes 実メモリ（子孫は含まない）
     * @param children    yt-dlp が起動した子孫プロセス（ffmpeg など）
     */
    public record RecorderUsage(
            int pid, String name, String label, Double cpuPercent, long memoryBytes,
            List<ProcessUsage> children
    ) {}

    /**
     * アプリが起動した、録画プロセス以外の外部プロセス 1 つ分のリソース（「端末に保存」の yt-dlp、耳キスの検出・詰め替え・
     * サムネイルの ffmpeg など）。
     *
     * <p>録画プロセスと分けるのは、録画プロセスは「どのチャンネルを録っているか」で見分けるのに対し、
     * こちらは「何のために動いているか」で見分けるため。
     *
     * @param pid         プロセス ID
     * @param name        プロセス名
     * @param purpose     用途（「端末に保存」「耳キスの検出」など）。見分けられなければ「その他」
     * @param cpuPercent  CPU 使用率（子孫は含まない）
     * @param memoryBytes 実メモリ（子孫は含まない）
     * @param children    このプロセスが起動した子孫プロセス（yt-dlp が起動する ffmpeg など）
     */
    public record HelperUsage(
            int pid, String name, String purpose, Double cpuPercent, long memoryBytes,
            List<ProcessUsage> children
    ) {}

    /**
     * 録画プロセス・その他の外部プロセスの子孫 1 つ分のリソース。
     *
     * @param pid         プロセス ID
     * @param name        プロセス名
     * @param cpuPercent  CPU 使用率
     * @param memoryBytes 実メモリ
     */
    public record ProcessUsage(int pid, String name, Double cpuPercent, long memoryBytes) {}

    /**
     * 目安を超えている項目。
     *
     * @param key     画面が項目を見分けるための固定の識別子（{@code cpu} / {@code memory} / {@code swap} / {@code disk}）
     * @param message 表示する文言
     */
    public record Warning(String key, String message) {}
}
