package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.UserSubscription;
import com.example.monitor.platform.Platform;
import com.example.monitor.util.StreamLinkUtils;

import java.time.LocalDateTime;

/**
 * 利用者が購読しているチャンネル 1 件。ユーザー画面の一覧に使う。
 *
 * <h2>管理者向けの {@link MonitoredChannelResponse} と分けている理由</h2>
 * 録画の設定は<b>この購読者自身の希望</b>を返す。チャンネル単位の設定をそのまま見せると、
 * 他の購読者や管理者の希望が自分の設定として表示されてしまう。
 * 実際に録画されるかは「誰か 1 人でも希望していれば録画する」で決まる
 * （{@code RecordingIntentResolver} 参照）ため、<b>自分が OFF でも録画されることはある</b>。
 *
 * <p>DB の主キー（{@code id}）は購読の解除に使うので返している。
 *
 * @param id                  チャンネルの主キー。購読の解除に使う
 * @param platform            プラットフォーム
 * @param platformLabel       画面表示用のプラットフォーム名
 * @param channelName         チャンネル名
 * @param youtubeChannelId    プラットフォームが発行するチャンネル識別子
 * @param currentlyLive       最終観測時点で配信中か
 * @param currentLiveVideoId  配信中の動画 ID。配信していなければ {@code null}
 * @param lastCheckedAt       最後に巡回した時刻
 * @param detectionFailing    配信状態を判定できていない状態が続いているか。
 *                            <b>「配信していない」と区別して画面に出すために要る</b>
 * @param subscribedAt        購読した時刻
 * @param recordEnabled       この購読者が自動録画を希望しているか
 * @param recordTitleKeywords この購読者の絞り込みキーワード。未設定なら {@code null}
 * @param channelUrl          チャンネルページの URL。Twitch でログイン名をまだ取得できていない場合は {@code null}
 * @param channelIconUrl      チャンネルのアイコン URL。まだ読み取れていない場合は {@code null}
 * @param recordingCount      再生できる録画の件数（状態が {@code COMPLETED} と {@code PARTIAL} のもの）。
 *                            管理者の一覧と数え方をそろえ、画面ごとに件数が食い違わないようにしている
 */
public record SubscribedChannelResponse(
        Long id,
        Platform platform,
        String platformLabel,
        String channelName,
        String youtubeChannelId,
        boolean currentlyLive,
        String currentLiveVideoId,
        LocalDateTime lastCheckedAt,
        boolean detectionFailing,
        LocalDateTime subscribedAt,
        boolean recordEnabled,
        String recordTitleKeywords,
        String channelUrl,
        String channelIconUrl,
        long recordingCount
) {

    /**
     * 購読とその対象チャンネルから応答を組み立てる。録画件数は 0 とする。
     *
     * <p>購読の追加・設定変更の応答で使う。件数のためだけに録画を数える問い合わせを
     * 走らせないよう、件数が要る一覧（{@code GET /api/my/channels}）だけが
     * {@link #from(UserSubscription, long)} で実数を渡す。
     *
     * @param subscription 購読 1 件
     * @return 応答
     */
    public static SubscribedChannelResponse from(UserSubscription subscription) {
        return from(subscription, 0L);
    }

    /**
     * 購読とその対象チャンネル、録画件数から応答を組み立てる。
     *
     * @param subscription   購読 1 件
     * @param recordingCount 再生できる録画の件数
     * @return 応答
     */
    public static SubscribedChannelResponse from(UserSubscription subscription, long recordingCount) {
        MonitoredChannel channel = subscription.getChannel();
        return new SubscribedChannelResponse(
                channel.getId(),
                channel.getPlatform(),
                channel.getPlatform().displayName(),
                channel.getChannelName(),
                channel.getYoutubeChannelId(),
                channel.isCurrentlyLive(),
                channel.getCurrentLiveVideoId(),
                channel.getLastCheckedAt(),
                channel.getConsecutiveDetectionFailures() > 0,
                subscription.getSubscribedAt(),
                subscription.isRecordEnabled(),
                subscription.getRecordTitleKeywords(),
                StreamLinkUtils.channelUrl(
                        channel.getPlatform(), channel.getYoutubeChannelId(), channel.getChannelLogin()),
                channel.getChannelIconUrl(),
                recordingCount);
    }
}
