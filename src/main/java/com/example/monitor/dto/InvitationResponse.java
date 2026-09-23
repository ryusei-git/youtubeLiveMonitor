package com.example.monitor.dto;

import com.example.monitor.entity.Invitation;

import java.time.LocalDateTime;

/**
 * 管理者向けの招待 1 件。
 *
 * <h2>招待リンクの URL をサーバーが組み立てない理由</h2>
 * このサービスは LAN からも Tailscale 経由でも開かれるため、
 * <b>サーバー自身が「外から見える自分の URL」を正しく知る手段がない</b>
 * （{@code localhost} や内部 IP を返すと、受け取った相手が開けないリンクになる）。
 * そのため token だけを返し、リンクは<b>管理者のブラウザが今開いている URL を基準に</b>
 * 組み立てる。
 *
 * @param id               招待の主キー
 * @param token            招待リンクに載せる文字列。<b>使用済み・期限切れの場合は返さない</b>
 * @param label            誰に送ったかの覚え書き
 * @param createdAt        発行した時刻
 * @param expiresAt        期限
 * @param acceptedAt       使われた時刻。未使用なら {@code null}
 * @param acceptedUsername この招待で作られた利用者名。未使用なら {@code null}
 * @param usable           今この招待が使えるか
 */
public record InvitationResponse(
        Long id,
        String token,
        String label,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime acceptedAt,
        String acceptedUsername,
        boolean usable
) {

    /**
     * 招待から応答を組み立てる。
     *
     * <p><b>使えない招待の token は返さない。</b>もう意味を持たない秘密を
     * 画面や通信経路へ配り続ける理由がないため。
     *
     * @param invitation 招待
     * @param now        使用可否の判定に使う時刻
     * @return 応答
     */
    public static InvitationResponse from(Invitation invitation, LocalDateTime now) {
        boolean usable = invitation.isUsable(now);
        return new InvitationResponse(
                invitation.getId(),
                usable ? invitation.getToken() : null,
                invitation.getLabel(),
                invitation.getCreatedAt(),
                invitation.getExpiresAt(),
                invitation.getAcceptedAt(),
                invitation.getAcceptedUsername(),
                usable);
    }
}
