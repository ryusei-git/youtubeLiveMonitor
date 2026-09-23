package com.example.monitor.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 1 本の配信の詳細情報。通知本文の組み立てに使う。
 *
 * <p>DB に保存される情報ではなく、「配信を検知したので通知文を組み立てる」ために
 * その場で取得して使い捨てる入れ物。永続化対象の情報は
 * {@link com.example.monitor.entity.MonitoredChannel} 側にある。
 *
 * <p>元々は YouTube 専用だったため、{@link #youtubeChannelId} のように
 * 名前が YouTube に寄ったままの項目がある。Twitch など他のプラットフォームでは
 * そのプラットフォームでの識別子が入る。
 */
@Getter
@Builder
public class LiveStreamDetails {

    /** 配信の動画 ID（{@code youtube.com/watch?v=} の後ろに来る 11 文字）。 */
    private final String videoId;

    /** 配信タイトル。 */
    private final String title;

    /** 配信者の YouTube チャンネル ID。 */
    private final String youtubeChannelId;

    /** 配信者のチャンネル名（YouTube 上の正式名称）。 */
    private final String channelTitle;

    /** 配信の説明文。 */
    private final String description;

    /** 配信開始予定時刻。予約枠が作られていない配信では {@code null}。 */
    private final LocalDateTime scheduledStartTime;

    /** 実際に配信が始まった時刻。まだ始まっていなければ {@code null}。 */
    private final LocalDateTime actualStartTime;

    /**
     * YouTube API が返す配信状態。{@code "live"}（配信中）、{@code "upcoming"}（予約済み）、
     * {@code "none"}（配信ではない通常の動画）のいずれか。
     */
    private final String broadcastStatus;

    /** サムネイル画像の URL。取得できなかった場合は {@code null}。 */
    private final String thumbnailUrl;

    /**
     * 配信の視聴ページ URL。通知の埋め込みリンク先になる。
     *
     * <p><b>以前はここで {@code "https://www.youtube.com/watch?v=" + videoId} を
     * 組み立てて返していたが、それでは Twitch の配信を通知したときに
     * 存在しない YouTube の URL が貼られてしまう</b>ため、組み立てを各プラットフォームの
     * 担当へ移し、ここには結果だけを受け取る形にした。
     */
    private final String watchUrl;
}
