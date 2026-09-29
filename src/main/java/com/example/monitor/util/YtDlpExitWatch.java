package com.example.monitor.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * yt-dlp（録画・URL 指定のダウンロード）の終了を、出力と空き容量を見回りながら待ち、終わった後に結合の書きかけを片付ける。
 *
 * <h2>なぜ共有するのか</h2>
 * #426 の固まり検知は {@code StreamRecorder} の private メソッドにしか入らなかった。URL 指定のダウンロード
 * （{@code VideoDownloadService}）は {@link Process#waitFor()} で上限なく待ち、yt-dlp が固まると録画履歴は
 * {@code RECORDING} のまま、動画 ID も予約されたまま残った（削除は 409、後始末も「処理中」として触らない）。
 * どちらも同じ yt-dlp を起動し、出力の形（{@code {動画ID}.*}・結合の書きかけ {@code {動画ID}.temp.mp4}）も同じなので、
 * 見回りと片付けをここ 1 か所にまとめ、片方だけ直すずれを防ぐ。
 *
 * <h2>共有しないもの</h2>
 * 救済（{@code RecordingSalvager}）・結果の記録・録り直しは呼び出し側に残す。録り直し・管理画面から止めた印・
 * 途中からの録り直しの {@code PARTIAL}・失敗の通知があるのは録画だけで、まとめるとどちらの都合か分からない
 * 分岐が増えるため（{@code VideoDownloadService} のクラス JavaDoc と同じ考え方）。
 *
 * <p>管理者への通知を {@link Consumer} で受け取るのは、{@code util} は Bean に依存しない決まり
 * （{@code package-info.java}）だから。呼び出し側が {@code DiscordNotifier::sendAdminAlert} を渡す。
 *
 * <p>固まりの判定材料は {@link RecordingActivity} のとおりで、録画ファイルと yt-dlp のログの両方を見る。
 * 止め方は {@link ProcessTermination#terminateTreeAndAwait(ProcessHandle, Duration)} のとおりで、子孫ごと止める。
 */
@Slf4j
public final class YtDlpExitWatch {

    private YtDlpExitWatch() {
    }

    /**
     * yt-dlp のプロセスの待機（{@link YtDlpExitWatch#awaitExit}）の結果。
     *
     * @param exitCode    {@code yt-dlp} の終了コード。待機が中断された場合は {@code null}
     * @param stoppedByUs 出力が止まっていた、または空き容量が下限を割ったため、こちらから止めた場合 {@code true}
     */
    public record ExitResult(Integer exitCode, boolean stoppedByUs) {
    }

    /**
     * yt-dlp の終了を待つ。出力が {@code stallMinutes} 分止まっていたら、または空き容量が下限を割ったら、子孫ごと止める。
     *
     * <p>出力はファイルへ向けているため（{@code StreamRecorder} のクラス JavaDoc「プロセスの生存期間」参照）、ここでは読まない。
     * 1 分ごとに {@link RecordingActivity#lastModified(Path, String)} を見る。待機を始めた時刻を下限にするのは、
     * 起動直後でまだ何も書かれていないときと、録り直しで待ち直したときに、古い時刻で誤って止めないため。
     * 更新時刻を読めなかったときは止めない（「判定できなかった」を「固まった」と扱わない）。
     *
     * <p><b>空き容量（{@link #usableBytesIfBelowReserve}）は固まりの判定より先に見る。</b>固まりの判定は
     * 更新時刻を読めないときや止まっていないときに {@code continue} で次の 1 分へ飛ぶので、後に置くと
     * そのたびに空き容量を見なくなる。止める理由は {@code StreamRecorder} のクラス JavaDoc を参照。
     *
     * <p>呼び出し側は、{@code stoppedByUs} が {@code true} のとき録り直さないこと（配信中でも同じ止まり方を繰り返しうる）。
     *
     * @param process         起動済みの yt-dlp のプロセス
     * @param kind            ログと通知の文面に使う処理の名前（{@code "録画"} か {@code "ダウンロード"}）
     * @param channelName     ログと通知に出すチャンネル名（紐づくチャンネルが無ければ {@code "(未登録)"}）
     * @param videoId         対象の動画 ID
     * @param outputDirectory 出力先のフォルダ
     * @param stallMinutes    この分数だけ出力が更新されなければ止める
     * @param minFreeGb       始めるときのしきい値（GB）。見回りの下限は {@link DiskSpaceUtils#reserveBytes(long)}（0 以下なら見ない）
     * @param adminAlert      止めたことを管理者へ知らせる送り先（{@code DiscordNotifier::sendAdminAlert}）
     * @return 終了コード（待機が中断された場合は {@code null}。割り込み状態は立て直す）と、こちらから止めたか
     */
    public static ExitResult awaitExit(Process process, String kind, String channelName, String videoId,
                                       Path outputDirectory, long stallMinutes, long minFreeGb,
                                       Consumer<String> adminAlert) {
        Instant watchStart = Instant.now();
        try {
            while (!process.waitFor(1, TimeUnit.MINUTES)) {
                Long usableBytes = usableBytesIfBelowReserve(outputDirectory, minFreeGb);
                if (usableBytes != null) {
                    long reserveBytes = DiskSpaceUtils.reserveBytes(minFreeGb);
                    log.warn("空き容量が下限を下回ったため、{}プロセスを止めます: channel={}, video={}, 空き={}MB, 下限={}MB",
                            kind, channelName, videoId, usableBytes / (1024L * 1024), reserveBytes / (1024L * 1024));
                    if (ProcessTermination.terminateTreeAndAwait(process.toHandle(), Duration.ofSeconds(30))) {
                        Thread.currentThread().interrupt();
                        return new ExitResult(null, true);
                    }
                    try {
                        adminAlert.accept("空き容量が " + usableBytes / (1024L * 1024 * 1024) + "GB（下限 "
                                + reserveBytes / (1024L * 1024 * 1024) + "GB）を下回ったため、" + kind + "を止めました: "
                                + channelName + "（" + videoId + "）。止めるまでの分は、空きができた後の後始末で"
                                + "再生できる形に直します（詰め替えには" + kind + "と同じ大きさの空きが要ります）。");
                    } catch (RuntimeException e) {
                        // 通知の失敗で結果の記録を妨げない
                        log.warn("空き容量で{}プロセスを止めたことを管理者へ通知できませんでした", kind, e);
                    }
                    return new ExitResult(process.waitFor(), true);
                }
                Instant lastActivity;
                try {
                    Instant modified = RecordingActivity.lastModified(outputDirectory, videoId);
                    lastActivity = modified.isAfter(watchStart) ? modified : watchStart;
                } catch (IOException e) {
                    log.warn("{}の出力の更新時刻を読めなかったため、固まっているかの判定を見送ります: video={}", kind, videoId, e);
                    continue;
                }
                if (Duration.between(lastActivity, Instant.now()).toMinutes() < stallMinutes) {
                    continue;
                }

                log.warn("{}の出力が {}分間止まっているため、{}プロセスを止めます: channel={}, video={}, 最終更新={}",
                        kind, stallMinutes, kind, channelName, videoId, lastActivity);
                if (ProcessTermination.terminateTreeAndAwait(process.toHandle(), Duration.ofSeconds(30))) {
                    Thread.currentThread().interrupt();
                    return new ExitResult(null, true);
                }
                try {
                    adminAlert.accept(kind + "の出力が " + stallMinutes + " 分間止まっていたため、" + kind + "プロセスを止めました: "
                            + channelName + "（" + videoId + "）");
                } catch (RuntimeException e) {
                    // 通知の失敗で結果の記録を妨げない
                    log.warn("{}プロセスを止めたことを管理者へ通知できませんでした", kind, e);
                }
                return new ExitResult(process.waitFor(), true);
            }
            return new ExitResult(process.exitValue(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断された場合も、既にファイルが出来ていれば成功として扱う（呼び出し側で判定する）
            log.warn("{}の完了待ちが中断されました: video={}", kind, videoId);
            return new ExitResult(null, false);
        }
    }

    /**
     * 空き容量が見回りの下限（{@link DiskSpaceUtils#reserveBytes(long)}）を割っていれば、そのときの空きを返す。
     *
     * <p>空き容量を読めなかったときは割っていないと扱う（「判定できなかった」を「満杯」と扱わない。
     * {@code StreamRecorder.startRecording} と同じ）。
     *
     * @param outputDirectory 出力先のフォルダ
     * @param minFreeGb       始めるときのしきい値（GB）
     * @return 下限を割っていれば空き容量（バイト）。割っていない・確認しない（{@code minFreeGb} が 0）・読めなかった場合は {@code null}
     */
    private static Long usableBytesIfBelowReserve(Path outputDirectory, long minFreeGb) {
        long reserveBytes = DiskSpaceUtils.reserveBytes(minFreeGb);
        if (reserveBytes <= 0) {
            return null;
        }
        DiskSpaceUtils.Capacity disk = DiskSpaceUtils.read(outputDirectory);
        if (disk.error() != null || disk.usableBytes() == null || disk.usableBytes() >= reserveBytes) {
            return null;
        }
        return disk.usableBytes();
    }

    /**
     * yt-dlp が結合・修正の途中で書いていた {@code {動画ID}.temp.mp4} を消す。
     *
     * <p>yt-dlp は結合（Twitch では修正）に失敗すると、この書きかけ（再生できない）を消さずに終わる。
     * 空き容量が足りずに失敗した場合は空きを 0 まで使い切った大きさで残り、空きを塞ぎ続けて H2 の書き込みも
     * 救済（{@code RecordingSalvager}）も止めたままにする。こちらから止めた場合も同じく残る。
     * yt-dlp は結合が成功すると {@code .temp.mp4} を {@code .mp4} へ置き換え、結合の ffmpeg の終わりを待ってから
     * 終わるので、プロセスが終わった後に残っているものは必ず書きかけ。
     * 録画とダウンロードの両方から、プロセスが終わった後に呼ぶ。
     * <b>プロセスが終わったことを確かめた後（{@code ExitResult.exitCode()} が {@code null} でないとき）にだけ呼ぶ。</b>
     *
     * @param outputDirectory 出力先のフォルダ
     * @param videoId         対象の動画 ID
     */
    public static void deleteMergeLeftover(Path outputDirectory, String videoId) {
        Path leftover = outputDirectory.resolve(videoId + ".temp.mp4");
        try {
            if (Files.deleteIfExists(leftover)) {
                log.warn("yt-dlp の結合途中のファイルが残っていたため消しました（結合に失敗したか、途中で止めたため）: video={}, file={}",
                        videoId, leftover);
            }
        } catch (IOException e) {
            log.warn("結合途中のファイルを消せませんでした: video={}, file={}", videoId, leftover, e);
        }
    }
}
