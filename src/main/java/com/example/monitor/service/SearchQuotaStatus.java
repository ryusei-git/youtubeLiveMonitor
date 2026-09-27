package com.example.monitor.service;

import java.time.Instant;

/**
 * 画面に出す、YouTube の検索の「今日の残り」。
 *
 * <p>回数を使い切ってから弾かれるより、先に残りが見えている方が利用者は検索の使い方を選べるため。
 *
 * @param interactiveRemaining その場の検索に全体で残っている回数（全員で共有する枠）
 * @param userRemaining        その利用者が今日あと何回検索できるか（全体の残りを超えない）
 * @param resetsAt             回数が戻る時刻（米国太平洋時間の次の 0 時）
 */
public record SearchQuotaStatus(int interactiveRemaining, int userRemaining, Instant resetsAt) {}
