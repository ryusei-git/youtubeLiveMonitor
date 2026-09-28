package com.example.monitor.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.io.IOException;

/**
 * 録画の保存先があるボリュームの容量を調べる。
 *
 * <p>保存先が未作成でも、その親が属するボリュームの空き容量を確認するための処理。
 */
public final class DiskSpaceUtils {
    private DiskSpaceUtils() {}
    /**
     * 指定したディレクトリがあるボリュームの総容量と空き容量を返す。
     *
     * <p>取得失敗をゼロ容量と誤認させないため、値と失敗理由を分ける。
     * ディレクトリが無ければ、存在する親までさかのぼって調べる。
     *
     * @param directory 調べるディレクトリ（未作成でもよい）
     * @return 容量。取得できなければ容量は {@code null} で、{@code error} に理由が入る（例外は投げない）
     */
    public static Capacity read(Path directory) {
        Path path = directory.toAbsolutePath();
        while (path != null && !Files.exists(path)) path = path.getParent();
        try {
            if (path == null) throw new IOException("保存先の親を確認できません");
            var store = Files.getFileStore(path);
            return new Capacity(store.getTotalSpace(), store.getUsableSpace(), null, LocalDateTime.now());
        } catch (IOException | SecurityException e) {
            return new Capacity(null, null, "保存先ボリュームの容量を取得できません", LocalDateTime.now());
        }
    }
    /**
     * 空き容量がしきい値を下回っているかを返す。
     *
     * <p>「サービスに保存」（{@code VideoDownloadService}）と「端末に保存」（{@code DeviceDownloadService}）が
     * 同じ基準で断るために切り出した。<b>容量を読めなかったときは下回っていないと扱う</b>
     * （「判定できなかった」を「満杯」と扱わない。{@code StreamRecorder} と同じ）。
     *
     * @param directory 書き込み先のディレクトリ（未作成でもよい）
     * @param minFreeGb しきい値（GB）。0 以下なら確認しない
     * @return 空き容量を読めて、しきい値を下回っていれば {@code true}
     */
    public static boolean isBelow(Path directory, long minFreeGb) {
        if (minFreeGb <= 0) return false;
        Capacity disk = read(directory);
        return disk.error() == null && disk.usableBytes() != null
                && disk.usableBytes() < minFreeGb * 1024L * 1024 * 1024;
    }
    /**
     * 録画中と詰め替えのときに割らせない空き容量の下限（バイト）を返す。録画を始めるしきい値の 1/4。
     *
     * <p>始めるしきい値（{@code monitor.recording.min-free-gb}）を割っても、始まっている録画は書き続けるので
     * 空きはさらに減る。0 まで減ると録画が壊れるだけでなく、同じファイルシステムにある H2 とログの書き込みが
     * 失敗して監視・通知まで止まる。そこで手前にもう 1 本線を引き、録画はそこで止め（{@code StreamRecorder}）、
     * 詰め替えはそこを割るなら始めない（{@code RecordingSalvager}）。
     *
     * <p>別の設定にしないのは、下限を始めるしきい値より大きくされたときに「始めた録画がすぐ止まる」という
     * 食い違いを起こさないため（1/4 なら常にしきい値より小さい）。
     *
     * @param minFreeGb 録画を始めるしきい値（GB）。0 以下なら確認しない
     * @return 下限（バイト）。{@code minFreeGb} が 0 以下なら 0（確認しない）
     */
    public static long reserveBytes(long minFreeGb) {
        return minFreeGb <= 0 ? 0 : minFreeGb * 1024L * 1024 * 1024 / 4;
    }
    /**
     * ボリュームの容量。
     *
     * <p>録画ファイルの合計とは別の指標として表示する。
     *
     * @param totalBytes 総容量（取得できなければ {@code null}）
     * @param usableBytes 空き容量（取得できなければ {@code null}）
     * @param error 取得できなかった理由（取得できれば {@code null}）
     * @param checkedAt 調べた日時
     */
    public record Capacity(Long totalBytes, Long usableBytes, String error, LocalDateTime checkedAt) {}
}
