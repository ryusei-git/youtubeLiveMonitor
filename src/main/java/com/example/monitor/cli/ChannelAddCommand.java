package com.example.monitor.cli;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.exception.YouTubeApiUnavailableException;
import com.example.monitor.platform.Platform;
import com.example.monitor.service.MonitoredChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code channel add} コマンド。チャンネルを監視対象に追加する。
 *
 * <p>実行例:
 * <pre>{@code
 * java -jar app.jar channel add -i UCxxxxxxxxxxxxxxxxxxxxxx -n "配信者名"
 * java -jar app.jar channel add -i UCxxxxxxxxxxxxxxxxxxxxxx -n "配信者名" --record
 * java -jar app.jar channel add -p TWITCH -i https://www.twitch.tv/foo -n "配信者名"
 * }</pre>
 */
@Component
@Command(
        name = "add",
        mixinStandardHelpOptions = true,
        description = "チャンネルIDを指定して監視対象に追加する")
@RequiredArgsConstructor
public class ChannelAddCommand implements Callable<Integer> {

    private final MonitoredChannelService monitoredChannelService;

    @Option(names = {"-p", "--platform"},
            description = "配信プラットフォーム（候補: ${COMPLETION-CANDIDATES}、既定: ${DEFAULT-VALUE}）")
    private Platform platform = Platform.YOUTUBE;

    @Option(names = {"-i", "--id"}, required = true,
            description = "YouTube: チャンネルID（UC...）／ハンドル（@foo）／URL、"
                    + "Twitch: チャンネル名／URL")
    private String youtubeChannelId;

    @Option(names = {"-n", "--name"}, required = true,
            description = "画面やログに表示するチャンネル名")
    private String channelName;

    @Option(names = {"-r", "--record"},
            description = "配信を検知した際に自動で録画する（yt-dlpが必要）")
    private boolean recordEnabled;

    @Option(names = {"-k", "--keywords"},
            description = "通知・録画の対象を絞り込むキーワード（カンマ区切り、いずれか1つでも"
                    + "配信タイトルまたは配信カテゴリ（Twitchのみ）に含まれていれば対象。"
                    + "どちらにも含まれない配信は通知も録画もしない）")
    private String recordTitleKeywords;

    /**
     * チャンネルを登録する。
     *
     * @return 成功なら 0、既に登録済み・入力が解決できない・YouTube API を使えない場合は 1
     */
    @Override
    public Integer call() {
        try {
            MonitoredChannel registered =
                    monitoredChannelService.register(
                            platform, youtubeChannelId, channelName, recordEnabled, recordTitleKeywords);
            System.out.printf("登録しました: [%d] %s (%s / %s)%s%n",
                    registered.getId(), registered.getChannelName(),
                    registered.getPlatform().displayName(), registered.getYoutubeChannelId(),
                    registered.isRecordEnabled() ? " [録画ON]" : "");
            return 0;
        } catch (ChannelAlreadyRegisteredException e) {
            System.err.println(e.getMessage());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException | YouTubeApiUnavailableException e) {
            // 入力に該当するチャンネルが無い場合、Twitch の認証情報が未設定の場合、YouTube の API キーが無い・API が失敗した場合。
            // 例外の生スタックトレースではなく理由だけを出す
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
