package com.example.monitor.dto;

/**
 * チャンネルの自動録画 ON/OFF を切り替えるリクエスト。
 *
 * @param enabled 録画を有効にするか
 */
public record RecordToggleRequest(boolean enabled) {}
