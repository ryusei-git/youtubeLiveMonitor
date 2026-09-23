package com.example.monitor.dto;

/**
 * 購読ごとの録画設定を変更するリクエスト。
 *
 * <p>変えられるのは<b>自分の希望だけ</b>。他の購読者やチャンネル単位の設定には影響しない。
 *
 * @param enabled       自動録画を希望するか
 * @param titleKeywords 絞り込むキーワード（カンマ区切り）。空なら絞り込みなし
 */
public record SubscriptionRecordRequest(boolean enabled, String titleKeywords) {
}
