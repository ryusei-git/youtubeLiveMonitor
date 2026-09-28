package com.example.monitor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * YouTube の動画 1 件ぶんの情報。検索結果の 1 件と、視聴画面の動画の詳細で同じ形を使う。
 *
 * <p>タイトル・サムネイルは API の値をそのまま入れる（YouTube API の規約 III.C.5：書き換えない）。
 * 検索結果では {@code description} を先頭だけに縮め、{@code tags} を省く。
 * 視聴画面では全文と {@code tags} を入れる（一覧で全文を運ぶと、50 件で応答が重くなるため）。
 *
 * @param videoId                 動画 ID
 * @param title                   タイトル（API の値のまま）
 * @param description             説明文（検索結果では先頭だけ）
 * @param publishedAt             投稿日時
 * @param thumbnailUrl            サムネイルの URL（API の値のまま）
 * @param durationSeconds         長さ（秒）。配信中・予約枠は 0
 * @param viewCount               再生回数。取れなければ {@code null}
 * @param likeCount               高評価の数。非公開なら {@code null}
 * @param commentCount            コメント数。無効なら {@code null}
 * @param liveBroadcastContent    {@code none}・{@code live}・{@code upcoming}
 * @param scheduledStartTime      配信の開始予定。配信でなければ {@code null}
 * @param madeForKids             子ども向けか
 * @param channelId               チャンネル ID（{@code UC...}）
 * @param channelTitle            チャンネル名
 * @param channelIconUrl          チャンネルのアイコンの URL
 * @param channelSubscriberCount  チャンネル登録者数。非公開なら {@code null}
 * @param channelSubscriberHidden 登録者数が非公開か
 * @param channelVideoCount       チャンネルの動画数
 * @param registered              このサービスで監視中のチャンネルか（誰かが登録していれば true。見ている利用者の購読ではない。
 *                                画面は購読を {@code GET /api/my/channels} で見分け、「マイチャンネル」の札と登録のボタンを出し分ける）
 * @param saved                   このサービスに再生できる録画があるか
 * @param recordingId             その録画の ID。無ければ {@code null}
 * @param tags                    タグ。視聴画面の詳細だけに入れ、検索結果では省く
 */
public record YouTubeVideoResponse(
        String videoId,
        String title,
        String description,
        Instant publishedAt,
        String thumbnailUrl,
        long durationSeconds,
        Long viewCount,
        Long likeCount,
        Long commentCount,
        String liveBroadcastContent,
        Instant scheduledStartTime,
        boolean madeForKids,
        String channelId,
        String channelTitle,
        String channelIconUrl,
        Long channelSubscriberCount,
        boolean channelSubscriberHidden,
        Long channelVideoCount,
        boolean registered,
        boolean saved,
        Long recordingId,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> tags
) {

    /**
     * DB から引いた印（監視中・保存済み）を付け直した写しを返す。
     *
     * <p>API の値は 6 時間使い回すが、印は DB の今の状態を出したいので、返す直前に毎回付け直す。
     *
     * @param registered  監視中のチャンネルか
     * @param recordingId 再生できる録画の ID。無ければ {@code null}
     * @return 印を付けた写し
     */
    public YouTubeVideoResponse withMarks(boolean registered, Long recordingId) {
        return new YouTubeVideoResponse(videoId, title, description, publishedAt, thumbnailUrl, durationSeconds,
                viewCount, likeCount, commentCount, liveBroadcastContent, scheduledStartTime, madeForKids,
                channelId, channelTitle, channelIconUrl, channelSubscriberCount, channelSubscriberHidden,
                channelVideoCount, registered, recordingId != null, recordingId, tags);
    }
}
