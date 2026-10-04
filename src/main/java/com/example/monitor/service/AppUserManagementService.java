package com.example.monitor.service;

import com.example.monitor.dto.AppUserResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.exception.TooManyPasswordAttemptsException;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.security.LoginAttemptLimiter;
import com.example.monitor.util.PasswordPolicy;
import com.example.monitor.util.SecureTokens;
import com.example.monitor.util.UsernamePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

/**
 * 管理者が一般利用者を一覧・無効化・有効化・削除し、パスワードの再設定用のリンクを発行するための処理。
 * 利用者名の変更と、新しいパスワードの設定（#807）もここで扱う。
 *
 * <p>管理者の締め出しと権限昇格を防ぐため、操作できるのは一般利用者だけにしている
 * （管理者と自分自身は対象にできない）。
 *
 * <p><b>利用者名の変更とパスワードの設定では、管理者自身の今のパスワードを必ず照合する。</b>
 * どちらも対象の利用者として入れるようになる・本人を締め出せる操作なので、ログインしたまま席を外した管理者の画面や、
 * 盗まれたセッション・「ログインしたままにする」の Cookie だけでは行えないようにするため。
 * 照合には回数の上限を掛ける（{@code PasswordChangeService} の今のパスワードの照合と同じ理由・同じ上限）。
 *
 * <p>{@link PasswordEncoder} は cli プロファイルで作られないため {@code @Profile("!cli")}
 * （このクラスを使うのは画面の API だけ。付けないと CLI が Bean の不足で起動できなくなる）。
 */
@Profile("!cli")
@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserManagementService {
    /**
     * 再設定用のリンクの有効時間。招待（7 日）より短いのは、パスワードを決め直せる強い権限で、
     * 管理者が本人に渡してすぐ使われる前提だから（チャットの履歴に残ったリンクが長く生きないように）。
     */
    private static final long PASSWORD_RESET_VALID_HOURS = 24;

    /** ほかの人がすでに使っている利用者名に変えようとしたときの文言。 */
    private static final String USERNAME_TAKEN_MESSAGE = "その利用者名はすでに使われています";

    /** 読み込んだ後に対象が消えた・権限が変わったときの文言。 */
    private static final String STATE_CHANGED_MESSAGE = "利用者の状態が変わりました。再読み込みしてください";

    private final AppUserRepository repository;
    private final AuditLogger auditLogger;
    private final PasswordEncoder passwordEncoder;

    /**
     * 管理者自身のパスワードの照合の試行枠。上限はログインと同じ（利用者ごと 5 回・接続元ごと 20 回、15 分）。
     *
     * <p>ログイン用・{@code PasswordChangeService} 用の枠と分けるのは、{@code PasswordChangeService} と同じ理由
     * （利用者名と利用者 ID が同じ表で混ざらないように、ほかの経路の失敗でログインの枠まで減らさないように）。
     */
    private final LoginAttemptLimiter adminPasswordAttempts = new LoginAttemptLimiter();

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
     * 一般利用者の利用者名を変える（#807）。
     *
     * <p>要件と前後の空白の扱いは招待からの登録と同じ（{@link UsernamePolicy}）。ほかの人と同じ名前は断る。
     * 大文字小文字は登録と同じく区別する（登録の重複確認も一意制約も、大文字小文字を区別して比べている）。
     *
     * <p>変えたら、その利用者のログイン中のセッションは次のリクエストで失効する
     * （{@link AppUserRepository#updateUsername}。ログイン ID が変わるため）。
     *
     * <p><b>メソッドにトランザクションを掛けない。</b>失敗を監査ログに残してから例外を投げるので、
     * 1 つのトランザクションにすると失敗の記録ごとロールバックされる（{@code PasswordChangeService} と同じ）。
     * 書き込みはリポジトリの 1 本の UPDATE なので、途中で失敗しても何も変わらない。
     *
     * @param id            対象ID
     * @param username      新しい利用者名
     * @param adminPassword 操作者（管理者）自身の今のパスワード
     * @param actor         操作者の利用者名（認証情報由来。リクエストの本文からは受け取らない）
     * @param clientIp      操作元の IP。照合の回数の上限に使う
     * @throws ResponseStatusException 対象が無い（404）・管理者か自分（409）・管理者のパスワードが違う（403）・
     *                                 利用者名が要件を満たさない（400）・同じ名前がある（409）場合
     * @throws TooManyPasswordAttemptsException 管理者のパスワードの照合に続けて失敗し、一時的に制限している場合
     */
    public void rename(Long id, String username, String adminPassword, String actor, String clientIp) {
        AppUser target = checkTarget(id, actor);
        String oldName = target.getUsername();
        String newName;
        try {
            verifyAdminPassword(actor, adminPassword, clientIp);
            newName = toBadRequest(() -> UsernamePolicy.normalizeAndValidate(username));
            if (repository.findByUsername(newName).filter(user -> !user.getId().equals(id)).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, USERNAME_TAKEN_MESSAGE);
            }
            int updated;
            try {
                updated = repository.updateUsername(id, AppUser.Role.USER, newName, LocalDateTime.now());
            } catch (DataIntegrityViolationException e) {
                // 重複の確認の後に、同じ名前が先に付けられた（登録・別の管理者の変更）。一意制約で断る
                throw new ResponseStatusException(HttpStatus.CONFLICT, USERNAME_TAKEN_MESSAGE, e);
            }
            if (updated != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, STATE_CHANGED_MESSAGE);
            }
        } catch (ResponseStatusException e) {
            log.info("利用者名の変更を受け付けませんでした: id={}, actor={}, reason={}", id, actor, e.getReason());
            recordActorAction(AuditAction.USER_RENAME, AuditOutcome.FAILURE, actor, oldName, e.getReason());
            throw e;
        }
        log.info("利用者名を変更しました: id={}, actor={}, {} -> {}", id, actor, oldName, newName);
        // 対象は変えた後の名前。旧名は詳細に残し、前の名前での記録と結び付けられるようにする
        recordActorAction(AuditAction.USER_RENAME, AuditOutcome.SUCCESS, actor, newName, oldName + " → " + newName);
    }

    /**
     * 一般利用者の新しいパスワードを、管理者が決める（#807）。
     *
     * <p>パスワードは BCrypt のハッシュでしか保存しないので、管理者にも今のパスワードは見えない。
     * 代わりに新しいパスワードを決めて本人へ伝える。要件は一般利用者と同じ（{@link PasswordPolicy}）。
     * 変更時刻も書くので、その利用者のほかの端末のログインと「ログインしたままにする」は切れる。
     * 出ている再設定用のリンクは同じ UPDATE で消す（管理者が決めた後に古いリンクで上書きされないように）。
     *
     * <p>トランザクションを掛けない理由は {@link #rename} と同じ。パスワードの値はログにも監査ログにも書かない。
     *
     * @param id            対象ID
     * @param password      対象の利用者の新しいパスワード
     * @param adminPassword 操作者（管理者）自身の今のパスワード
     * @param actor         操作者の利用者名（認証情報由来。リクエストの本文からは受け取らない）
     * @param clientIp      操作元の IP。照合の回数の上限に使う
     * @throws ResponseStatusException 対象が無い（404）・管理者か自分（409）・管理者のパスワードが違う（403）・
     *                                 新しいパスワードが要件を満たさない（400）場合
     * @throws TooManyPasswordAttemptsException 管理者のパスワードの照合に続けて失敗し、一時的に制限している場合
     */
    public void setPassword(Long id, String password, String adminPassword, String actor, String clientIp) {
        AppUser target = checkTarget(id, actor);
        try {
            verifyAdminPassword(actor, adminPassword, clientIp);
            toBadRequest(() -> {
                PasswordPolicy.validate(AppUser.Role.USER, password);
                return null;
            });
            String hash = passwordEncoder.encode(password);
            if (repository.setPasswordByAdmin(id, AppUser.Role.USER, hash, LocalDateTime.now()) != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, STATE_CHANGED_MESSAGE);
            }
        } catch (ResponseStatusException e) {
            log.info("利用者のパスワードの設定を受け付けませんでした: id={}, actor={}, reason={}", id, actor, e.getReason());
            recordActorAction(AuditAction.PASSWORD_SET_BY_ADMIN, AuditOutcome.FAILURE, actor,
                    target.getUsername(), e.getReason());
            throw e;
        }
        log.info("利用者のパスワードを設定しました: id={}, actor={}", id, actor);
        recordActorAction(AuditAction.PASSWORD_SET_BY_ADMIN, AuditOutcome.SUCCESS, actor, target.getUsername(), null);
    }

    /**
     * 操作者（管理者）自身の今のパスワードを照合する。
     *
     * <p>操作者は認証情報の名前から引く（リクエストの本文に操作者を書かせると、他人のパスワードで通せてしまう）。
     * 照合の前に枠を確保し、合っていれば連続失敗を 0 に戻す（{@code PasswordChangeService#changePassword} と同じ）。
     * 制限中の拒否は監査ログに書かない（総当たりが続いても行数が際限なく増えないように。同上）。
     *
     * @param actor         操作者の利用者名（認証情報由来）
     * @param adminPassword 入力された操作者のパスワード
     * @param clientIp      操作元の IP
     * @throws ResponseStatusException パスワードが違う場合（403）
     * @throws TooManyPasswordAttemptsException 続けて失敗し、一時的に制限している場合
     */
    private void verifyAdminPassword(String actor, String adminPassword, String clientIp) {
        AppUser admin = repository.findByUsername(actor)
                .orElseThrow(() -> new IllegalStateException("ログイン中の管理者が見つかりません: " + actor));
        // 接続元が分からない（要求の外）ことは無いはずだが、枠の表のキーに null を入れないよう空文字にする
        LoginAttemptLimiter.Attempt attempt = adminPasswordAttempts.begin(Long.toString(admin.getId()),
                clientIp == null ? "" : clientIp);
        if (!attempt.allowed()) {
            log.info("管理者のパスワードの照合を一時制限中のため受け付けませんでした: actor={}, retryAfterSeconds={}",
                    actor, attempt.retryAfterSeconds());
            throw new TooManyPasswordAttemptsException(attempt.retryAfterSeconds());
        }
        boolean matched = false;
        try {
            matched = adminPassword != null && passwordEncoder.matches(adminPassword, admin.getPasswordHash());
        } finally {
            adminPasswordAttempts.finish(attempt, matched);
        }
        if (!matched) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理者のパスワードが違います");
        }
    }

    /**
     * 要件の違反（{@link IllegalArgumentException}）を 400 にする。文言は要件のクラスのものをそのまま画面に出す。
     *
     * @param check 要件を調べる処理
     * @param <T>   戻り値の型
     * @return 処理の戻り値
     */
    private static <T> T toBadRequest(Supplier<T> check) {
        try {
            return check.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
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
        recordActorAction(action, AuditOutcome.SUCCESS, actor, target.getUsername(), null);
    }

    /**
     * 操作者（管理者）の利用者IDを解決して、結果と補足付きで監査ログへ記録する。パスワードの値は渡さないこと。
     *
     * @param action     操作の種別
     * @param outcome    結果
     * @param actor      操作者の利用者名
     * @param targetName 操作対象の利用者名
     * @param detail     失敗の理由・変更の内容。無ければ {@code null}
     */
    private void recordActorAction(AuditAction action, AuditOutcome outcome, String actor, String targetName,
                                   String detail) {
        Long actorId = repository.findByUsername(actor).map(AppUser::getId).orElse(null);
        auditLogger.record(action, outcome, actorId, actor, null, "USER", targetName, detail);
    }
}
