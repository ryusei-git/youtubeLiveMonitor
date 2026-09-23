package com.example.monitor.dto;

/**
 * 「今すぐチェック」の実行結果。
 *
 * <p>既に巡回中で実行できなかった場合はこの型ではなく
 * {@link com.example.monitor.exception.MonitoringInProgressException} による
 * 409 Conflict が返るため、この型は成功時だけを表す。
 *
 * @param checkedChannels 巡回対象となったチャンネル数
 */
public record ManualCheckResponse(long checkedChannels) {}
