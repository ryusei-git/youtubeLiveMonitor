package com.example.monitor.cli;

import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.service.MonitoredChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code channel remove} コマンド。監視対象からチャンネルを削除する。
 *
 * <p>削除するとそのチャンネルの通知履歴も一緒に消える点に注意。
 *
 * <p>録画中のチャンネルなら、yt-dlp が止まるまで待ってから終わる。理由は定数 {@code STOP_TIMEOUT} の JavaDoc を参照。
 */
@Component
@Command(
        name = "remove",
        mixinStandardHelpOptions = true,
        description = "監視対象からチャンネルを削除する（通知履歴も一緒に削除される。録画中なら yt-dlp が止まるまで最大 2 分待つ）")
@RequiredArgsConstructor
public class ChannelRemoveCommand implements Callable<Integer> {

    /**
     * 録画中の yt-dlp が止まるのを待つ上限。
     *
     * <p>CLI はコマンドが終わるとすぐ {@code System.exit} するため、待たないと止める処理が途中で打ち切られ、
     * 削除したチャンネルの録画が続く（{@link MonitoredChannelService#remove(Long)} 参照）。
     * 止める処理は 1 本ずつ、SIGTERM から最大 30 秒で SIGKILL に切り替える。1 チャンネルで同時に録画するのは
     * 普通 1 本なので、SIGTERM で止まらない録画が 3 本あっても間に合う 2 分にした。これを超えるのは、
     * SIGKILL でも終わらないとき（別のユーザーのプロセスで止める権限が無いなど）か、SIGTERM で止まらない録画が
     * 4 本以上あるときなので、待ち続けずに利用者へ確かめてもらう。
     */
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(2);

    private final MonitoredChannelService monitoredChannelService;

    @Option(names = {"-i", "--id"}, required = true,
            description = "削除する監視対象のID（channel list の先頭列。YouTube のチャンネルIDではない）")
    private Long channelRecordId;

    /**
     * 監視対象を削除する。
     *
     * @return 成功なら 0。指定 ID が存在しない場合と、録画中の yt-dlp を止め切れなかった場合は 1
     */
    @Override
    public Integer call() {
        CompletableFuture<Integer> recordingsStopped;
        try {
            recordingsStopped = monitoredChannelService.remove(channelRecordId);
        } catch (ChannelNotFoundException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        System.out.println("削除しました: ID " + channelRecordId);
        return awaitRecordingsStopped(recordingsStopped);
    }

    /**
     * 削除したチャンネルの録画中の yt-dlp が止まるのを、{@code STOP_TIMEOUT} まで待つ。
     *
     * <p>止めた数を出すのは、{@code cli} プロファイルではアプリのログが WARN 以上に絞られ、
     * 止めたかどうかが画面に出ないため。待ち切れなかったときも削除は済んでいるので、そのことを添える。
     *
     * @param recordingsStopped {@link MonitoredChannelService#remove(Long)} の戻り値
     * @return 止め終わったら 0、待ち切れなかった・失敗した・中断された場合は 1
     */
    private int awaitRecordingsStopped(CompletableFuture<Integer> recordingsStopped) {
        if (!recordingsStopped.isDone()) {
            System.out.println("録画中の yt-dlp を止めています（最大 " + STOP_TIMEOUT.toSeconds() + " 秒）...");
        }
        try {
            int stopped = recordingsStopped.get(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (stopped > 0) {
                System.out.println("録画中の yt-dlp を " + stopped + " 個止めました");
            }
            return 0;
        } catch (TimeoutException e) {
            System.err.println("録画中の yt-dlp を " + STOP_TIMEOUT.toSeconds() + " 秒以内に止め切れませんでした。"
                    + "ps -ef | grep yt-dlp で残っていないか確かめてください（チャンネルの削除は済んでいます）");
            return 1;
        } catch (ExecutionException e) {
            System.err.println("録画中の yt-dlp を止められませんでした: " + e.getCause()
                    + "（チャンネルの削除は済んでいます）");
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("録画中の yt-dlp が止まるのを待つ間に中断されました。"
                    + "ps -ef | grep yt-dlp で残っていないか確かめてください（チャンネルの削除は済んでいます）");
            return 1;
        }
    }
}
