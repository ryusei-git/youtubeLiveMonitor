package com.example.monitor.dto;

/**
 * 候補に答えるリクエスト（Issue #470）。
 *
 * <p>答えを enum ではなく文字列で受けるのは、enum だと知らない値が JSON の読み取りで失敗し、
 * 入力誤りの 400 ではなく「予期しないエラー」の 500 になるため（{@link SoundMarkRequest} と同じ。サービスで直して 400 にする）。
 *
 * @param verdict {@code CONFIRMED}（耳キス）・{@code REJECTED}（ちがう）・{@code null}（取り消し）
 */
public record SoundCandidateVerdictRequest(String verdict) {}
