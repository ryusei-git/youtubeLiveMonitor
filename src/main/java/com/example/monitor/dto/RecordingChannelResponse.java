package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.util.StreamLinkUtils;

import java.time.LocalDateTime;

/**
 * 利用者のアーカイブのチャンネル 1 件。チャンネル一覧のカードと、チャンネルページの見出しに使う。
 *
 * <p>購読しているチャンネル（{@link SubscribedChannelResponse}）とは分けている。アーカイブは購読していない
 * チャンネルの録画も見られる（#419）ので、ここでは全チャンネルを返し、購読しているかは {@code subscribed} で添える。
 * 録画の設定や通知の設定は、チャンネルを選んで録画を見る目的には要らないので持たない。
 *
 * @param id                   チャンネルの主キー。録画一覧の {@code channelId} に渡す
 * @param channelName          チャンネル名
 * @param platformLabel        画面表示用のプラットフォーム名
 * @param channelUrl           配信元のチャンネルページの URL。Twitch でログイン名をまだ取得できていない
 *                             場合は {@code null}
 * @param channelIconUrl       チャンネルのアイコン URL。まだ読み取れていない場合は {@code null}
 * @param currentlyLive        最終観測時点で配信中か
 * @param subscribed           ログイン中の利用者が購読しているか
 * @param recordingCount       再生できる録画（完了・途中まで）の件数。アーカイブの一覧に出る件数とそろえる
 * @param totalDurationSeconds 再生できる録画の長さの合計（秒）。長さが分からない録画は数えない
 * @param latestStartedAt      再生できる録画のうち、最新のものの録画開始時刻。録画が無ければ {@code null}
 * @param latestThumbnailPath  サムネイルのある最新の録画のサムネイル（録画フォルダーからの相対パス）。
 *                             無ければ {@code null}
 */
public record RecordingChannelResponse(
        Long id,
        String channelName,
        String platformLabel,
        String channelUrl,
        String channelIconUrl,
        boolean currentlyLive,
        boolean subscribed,
        long recordingCount,
        long totalDurationSeconds,
        LocalDateTime latestStartedAt,
        String latestThumbnailPath
) {

    /**
     * チャンネルと、その録画の集計から作る。
     *
     * @param channel              チャンネル
     * @param subscribed           ログイン中の利用者が購読しているか
     * @param recordingCount       再生できる録画の件数
     * @param totalDurationSeconds 再生できる録画の長さの合計（秒）
     * @param latestStartedAt      最新の録画開始時刻。録画が無ければ {@code null}
     * @param latestThumbnailPath  サムネイルのある最新の録画のサムネイル。無ければ {@code null}
     * @return 変換後のレスポンス
     */
    public static RecordingChannelResponse of(MonitoredChannel channel, boolean subscribed, long recordingCount,
                                              long totalDurationSeconds, LocalDateTime latestStartedAt,
                                              String latestThumbnailPath) {
        return new RecordingChannelResponse(
                channel.getId(),
                channel.getChannelName(),
                channel.getPlatform().displayName(),
                StreamLinkUtils.channelUrl(channel.getPlatform(), channel.getYoutubeChannelId(), channel.getChannelLogin()),
                channel.getChannelIconUrl(),
                channel.isCurrentlyLive(),
                subscribed,
                recordingCount,
                totalDurationSeconds,
                latestStartedAt,
                latestThumbnailPath);
    }
}
