package com.example.monitor.dto;

/**
 * 候補を判定するリクエスト（Issue #488）。
 *
 * <p>enum ではなく文字列で受ける。知らない値を JSON の読み取りで弾くと 400 ではなく 500 になるため
 * （{@link SoundCandidateVerdictRequest} と同じ）。
 *
 * @param status {@code VTUBER}・{@code REJECTED}・{@code CANDIDATE}（判定の取り消し）
 */
public record DiscoveryDecisionRequest(String status) {}
