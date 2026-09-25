package com.example.monitor.service;

import com.example.monitor.dto.AppUserResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.util.SecureTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 管理者が一般利用者を一覧・無効化・有効化・削除し、パスワードの再設定用のリンクを発行するための処理。
 *
 * <p>管理者の締め出しと権限昇格を防ぐため、操作できるのは一般利用者だけにしている
 * （管理者と自分自身は対象にできない）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserManagementService {
    /**
     * 再設定用のリンクの有効時間。招待（7 日）より短いのは、パスワードを決め直せる強い権限で、
     * 管理者が本人に渡してすぐ使われる前提だから（チャットの履歴に残ったリンクが長く生きないように）。
     */
    private static final long PASSWORD_RESET_VALID_HOURS = 24;

    private final AppUserRepository repository;
    private final AuditLogger auditLogger;

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
        AppUser target = checkTarget(id, actor);
        if (repository.disableUser(id, AppUser.Role.USER) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "利用者の状態が変わりました。再読み込みしてください");
        }
        log.info("利用者を無効化しました: id={}, actor={}", id, actor);
        recordActorAction(AuditAction.USER_DISABLE, actor, target);
    }

    /**
     * 無効化した利用者を有効に戻す（#327）。削除して招待し直すと購読・視聴済み・Webhook が連鎖で消えるため、
     * 一時的に止めた・誤って無効化した利用者はこちらで戻す。
     * @param id 対象ID
     * @param actor 操作者の利用者名（認証情報由来）
     */
    @Transactional
    public void enable(Long id, String actor) {
        AppUser target = checkTarget(id, actor);
        if (repository.enableUser(id, AppUser.Role.USER) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "利用者の状態が変わりました。再読み込みしてください");
        }
        log.info("利用者を有効に戻しました: id={}, actor={}", id, actor);
        recordActorAction(AuditAction.USER_ENABLE, actor, target);
    }

    /**
     * パスワードを忘れた利用者を、削除せずに戻すためのリンクの token を発行する（#324）。
     *
     * <p>管理者にもパスワードが分からないまま本人が決め直せるよう、パスワードではなく token を渡す。
     * 削除して招待し直すと、購読・視聴済み・Webhook が連鎖で消えるため。
     * URL は組み立てない（招待と同じく、画面が {@code location.origin} から組み立てる）。
     *
     * @param id    対象ID
     * @param actor 操作者の利用者名（認証情報由来）
     * @return 発行した token と期限
     */
    @Transactional
    public PasswordResetIssued issuePasswordReset(Long id, String actor) {
        AppUser target = checkTarget(id, actor);
        String token = SecureTokens.generate();
        // 画面にそのまま出すので秒に切り詰める
        LocalDateTime expiresAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                .plusHours(PASSWORD_RESET_VALID_HOURS);
        if (repository.updatePasswordResetToken(id, AppUser.Role.USER, token, expiresAt) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "利用者の状態が変わりました。再読み込みしてください");
        }
        // token は秘密なのでログにも監査ログにも出さない
        log.info("パスワードの再設定用のリンクを発行しました: id={}, actor={}", id, actor);
        recordActorAction(AuditAction.PASSWORD_RESET_ISSUE, actor, target);
        return new PasswordResetIssued(token, expiresAt);
    }

    /**
     * 発行した再設定用の token。エンティティを返さず、画面が要る 2 つだけを返す。
     *
     * @param token     再設定用のリンクに載せる秘密の文字列
     * @param expiresAt 期限
     */
    public record PasswordResetIssued(String token, LocalDateTime expiresAt) {
    }

    /**
     * 利用者と購読を削除しても、共有のチャンネル・録画や監査証跡は残す。
     * @param id 対象ID
     * @param actor 操作者の利用者名（認証情報由来）
     */
    @Transactional
    public void delete(Long id, String actor) {
        AppUser target = checkTarget(id, actor);
        if (repository.deleteUser(id, AppUser.Role.USER) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "利用者の状態が変わりました。再読み込みしてください");
        }
        log.info("利用者を削除しました: id={}, actor={}", id, actor);
        recordActorAction(AuditAction.USER_DELETE, actor, target);
    }

    /**
     * @param id     対象ID
     * @param actor  操作者の利用者名
     * @return 対象の利用者（呼び出し側で再度読み直さずに済むよう返す）
     */
    private AppUser checkTarget(Long id, String actor) {
        AppUser user = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "利用者が見つかりません"));
        if (user.getRole() != AppUser.Role.USER || user.getUsername().equals(actor)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "管理者・自分自身は操作できません");
        }
        return user;
    }

    /**
     * 操作者（管理者）の利用者IDを解決して監査ログへ記録する。
     *
     * @param action 操作の種別
     * @param actor  操作者の利用者名
     * @param target 操作対象の利用者
     */
    private void recordActorAction(AuditAction action, String actor, AppUser target) {
        Long actorId = repository.findByUsername(actor).map(AppUser::getId).orElse(null);
        auditLogger.record(action, AuditOutcome.SUCCESS, actorId, actor, null,
                "USER", target.getUsername(), null);
    }
}
