package com.example.monitor.service;

import com.example.monitor.dto.AppUserResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** 管理者の締め出しと権限昇格を防ぐため、一般利用者の無効化・削除だけを提供する。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserManagementService {
    private final AppUserRepository repository;

    /**
     * 一覧から秘密情報を辿れないようDTOへ変換する。
     * @return 登録の新しい順の利用者
     */
    @Transactional(readOnly = true)
    public List<AppUserResponse> list() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt", "id"))
                .stream().map(AppUserResponse::from).toList();
    }

    /**
     * 履歴を残しながらアクセスを止める。既存セッションも次のリクエストで失効する。
     * @param id 対象ID
     * @param actor 操作者の利用者名（認証情報由来）
     */
    @Transactional
    public void disable(Long id, String actor) {
        checkTarget(id, actor);
        if (repository.disableUser(id, AppUser.Role.USER) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "利用者の状態が変わりました。再読み込みしてください");
        }
        log.info("利用者を無効化しました: id={}, actor={}", id, actor);
    }

    /**
     * 利用者と購読を削除しても、共有のチャンネル・録画や監査証跡は残す。
     * @param id 対象ID
     * @param actor 操作者の利用者名（認証情報由来）
     */
    @Transactional
    public void delete(Long id, String actor) {
        checkTarget(id, actor);
        if (repository.deleteUser(id, AppUser.Role.USER) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "利用者の状態が変わりました。再読み込みしてください");
        }
        log.info("利用者を削除しました: id={}, actor={}", id, actor);
    }

    private void checkTarget(Long id, String actor) {
        AppUser user = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "利用者が見つかりません"));
        if (user.getRole() != AppUser.Role.USER || user.getUsername().equals(actor)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "管理者・自分自身は操作できません");
        }
    }
}
