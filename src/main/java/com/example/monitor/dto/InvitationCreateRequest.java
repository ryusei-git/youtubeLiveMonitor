package com.example.monitor.dto;

/**
 * 招待を発行するときのリクエスト。
 *
 * @param label     誰に送るかの覚え書き。省略可
 * @param validDays 有効日数。省略・0 以下なら既定値（{@code InvitationService.DEFAULT_VALID_DAYS}）
 */
public record InvitationCreateRequest(String label, Integer validDays) {
}
