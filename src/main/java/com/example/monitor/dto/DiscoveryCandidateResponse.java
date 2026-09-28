package com.example.monitor.dto;

import java.util.List;

/**
 * 新人発掘の候補 1 件を画面へ返す形（Issue #488）。
 *
 * <p>日時は ISO 8601 の文字列で返す（画面 #490 がこの形を前提にする）。「ちがう」の行は判定以外が {@code null}。
 *
 * @param channelId          YouTube のチャンネル ID
 * @param title              チャンネル名
 * @param iconUrl            アイコンの URL
 * @param channelUrl         チャンネルのページの URL
 * @param subscriberCount    登録者の数。非公開なら {@code null}
 * @param subscriberHidden   登録者の数が非公開か
 * @param videoCount         動画の数
 * @param channelPublishedAt チャンネルを作った日時
 * @param firstUploadAt      最も古い動画の公開日時
 * @param sampleVideoId      見つけた動画の ID
 * @param sampleVideoTitle   見つけた動画のタイトル
 * @param matchedWords       一致した語
 * @param foundByTerm        見つけた検索語。手動の登録では {@code null}
 * @param discoveredAt       見つけた日時。「候補に戻す」とその日時になる（判定されない候補を消す期限の起点）
 * @param refreshedAt        API の値を最後に取った日時（画面の値が「いつ時点か」、規約 III.E.4.f）
 * @param status             {@code CANDIDATE}・{@code VTUBER}・{@code REJECTED}
 * @param decidedBy          判定した利用者のログイン ID
 * @param registered         監視中のチャンネルか
 */
public record DiscoveryCandidateResponse(String channelId, String title, String iconUrl, String channelUrl,
        Long subscriberCount, boolean subscriberHidden, Long videoCount, String channelPublishedAt,
        String firstUploadAt, String sampleVideoId, String sampleVideoTitle, List<String> matchedWords,
        String foundByTerm, String discoveredAt, String refreshedAt, String status, String decidedBy,
        boolean registered) {}
