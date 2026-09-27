package com.example.monitor.dto;

/**
 * 候補を手動で登録するリクエスト（Issue #488）。
 *
 * @param url チャンネルの URL・ハンドル（{@code @foo}）・チャンネル ID のどれか
 */
public record DiscoveryAddRequest(String url) {}
