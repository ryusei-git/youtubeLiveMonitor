package com.example.monitor.controller;

import com.example.monitor.dto.InvitationCreateRequest;
import com.example.monitor.dto.InvitationResponse;
import com.example.monitor.service.InvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 招待リンクを発行・管理する API（管理者専用）。
 *
 * <p>パスを {@code /api/admin/} 配下に置いているのは、{@code SecurityConfig} で
 * 管理者限定にしていることをパスからも読み取れるようにするため。
 */
/** 依存する InvitationService が {@code !cli} のため、こちらにも付ける
 * （付けないと Bean 解決に失敗して CLI が起動できない）。 */
@Profile("!cli")
@RestController
@RequestMapping("/api/admin/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    /**
     * 招待の一覧を返す。
     *
     * @return 発行の新しい順の招待
     */
    @GetMapping
    public List<InvitationResponse> list() {
        return invitationService.list();
    }

    /**
     * 招待を発行する。
     *
     * @param request 覚え書きと有効日数
     * @return 発行した招待（token を含む）
     */
    @PostMapping
    public InvitationResponse issue(@RequestBody(required = false) InvitationCreateRequest request) {
        return request == null
                ? invitationService.issue(null, null)
                : invitationService.issue(request.label(), request.validDays());
    }

    /**
     * 招待を取り消す。
     *
     * @param id 招待の主キー
     * @return 取り消せたら 204、存在しなければ 404
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        return invitationService.revoke(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
