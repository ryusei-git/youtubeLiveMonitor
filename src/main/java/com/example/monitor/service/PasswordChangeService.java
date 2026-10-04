package com.example.monitor.service;

import com.example.monitor.dto.InvitationCheckResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.exception.TooManyPasswordAttemptsException;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.security.LoginAttemptLimiter;
import com.example.monitor.util.DatabaseUpdateVerifier;
import com.example.monitor.util.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * ログイン中の利用者が自分のパスワードを変える（#321）。一般利用者も管理者も同じ処理を使う。
 * 管理者が発行したリンクからの再設定（#324）もここで扱う。要件（{@link PasswordPolicy}）と
 * ほかのセッションの失効を、変更と再設定でずらさないため。
 *
 * <p><b>今のパスワードを必ず照合する。</b>ログインしたまま離れた端末を他人に触られても、
 * パスワードを変えてアカウントを乗っ取られないようにするため。
 *
 * <p><b>照合の回数には上限を掛ける。</b>上限が無いと、盗まれたセッションや remember-me の Cookie を持つ人が
 * ここで今のパスワードを総当たりし、当たったら変えて本人を締め出せる。照合が合ったかどうかを知っているのは
 * このクラスだけなので、枠の確保と返却もここで行う（{@link LoginAttemptLimiter}）。制限中の拒否は監査ログに
 * 書かない（ログインの制限中と同じく、総当たりが続いても行数が際限なく増えないようにするため）。
 *
 * <p><b>再設定で token に該当する利用者がいない失敗は監査ログに書かない。</b>理由は
 * {@link #resetPassword} のコメントのとおり。期限切れや短いパスワードのように利用者が分かる失敗は、
 * これまでどおり記録する。
 *
 * <p>変更時刻も同時に書き、それより前にログインしたほかのセッションを失効させる
 * （{@link AppUserRepository#isSessionValid}）。
 *
 * <p><b>メソッドにトランザクションを掛けない。</b>失敗を監査ログに残してから例外を投げるので、
 * 1 つのトランザクションにすると失敗の記録ごとロールバックされる。更新はリポジトリの 1 クエリで完結する。
 *
 * <p>{@link PasswordEncoder} は cli プロファイルで作られないため {@code @Profile("!cli")}。
 */
@Profile("!cli")
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordChangeService {

    /** 再設定用のリンクが見つからないときの理由。使用済み・上書き済み・存在しないを区別できない（使ったら消すため）。 */
    private static final String RESET_TOKEN_NOT_FOUND =
            "このリンクは使用済みか、新しいリンクが発行されたため使えません。管理者に再発行を依頼してください。";

    /** 再設定用のリンクの期限が切れているときの理由。 */
    private static final String RESET_TOKEN_EXPIRED = "このリンクは期限が切れています。管理者に再発行を依頼してください。";

    /** 監査ログの対象の種類。利用者管理の監査ログと揃える。 */
    private static final String AUDIT_TARGET_TYPE = "USER";

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogger auditLogger;

    /**
     * 今のパスワードの照合の試行枠。上限はログインと同じ（利用者ごと 5 回・接続元ごと 20 回、15 分）。
     *
     * <p><b>ログイン用の Bean を注入せず、別のインスタンスにする。</b>利用者名は長さ（3〜64 文字）しか
     * 制限していないので、同じ表に利用者 ID を入れると、「123」のような名前でログインに失敗しただけで
     * 利用者 ID 123 の枠が減ってしまう。また、パスワード変更の失敗でログインの接続元の枠まで減らさないため。
     */
    private final LoginAttemptLimiter currentPasswordAttempts = new LoginAttemptLimiter();

    /**
     * パスワードを変える。
     *
     * @param userId          ログイン中の利用者の主キー
     * @param currentPassword 今のパスワード
     * @param newPassword     新しいパスワード
     * @param clientIp        操作元の IP。監査ログに残す
     * @return 変更後の利用者。変更したセッションの主体を差し替えるのに使う
     * @throws IllegalArgumentException 今のパスワードが違う、または新しいパスワードが要件を満たさない場合
     * @throws TooManyPasswordAttemptsException 今のパスワードの照合に続けて失敗し、一時的に制限している場合
     */
    public AppUser changePassword(Long userId, String currentPassword, String newPassword, String clientIp) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("ログイン中の利用者が見つかりません: id=" + userId));
        LoginAttemptLimiter.Attempt attempt = currentPasswordAttempts.begin(Long.toString(userId), clientIp);
        if (!attempt.allowed()) {
            log.info("パスワードの変更を一時制限中のため受け付けませんでした: user={}, retryAfterSeconds={}",
                    user.getUsername(), attempt.retryAfterSeconds());
            throw new TooManyPasswordAttemptsException(attempt.retryAfterSeconds());
        }
        boolean matched = false;
        try {
            matched = currentPassword != null && passwordEncoder.matches(currentPassword, user.getPasswordHash());
        } finally {
            // 合っていれば連続失敗を 0 に戻す。新しいパスワードが要件を満たさないのは照合の失敗ではない
            currentPasswordAttempts.finish(attempt, matched);
        }
        try {
            if (!matched) {
                throw new IllegalArgumentException("今のパスワードが違います");
            }
            PasswordPolicy.validate(user.getRole(), newPassword);
        } catch (IllegalArgumentException e) {
            log.info("パスワードの変更を受け付けませんでした: user={}, reason={}", user.getUsername(), e.getMessage());
            record(user, AuditOutcome.FAILURE, clientIp, e.getMessage());
            throw e;
        }

        String hash = passwordEncoder.encode(newPassword);
        LocalDateTime changedAt = changedAtNow();
        DatabaseUpdateVerifier.verify(appUserRepository.updatePassword(userId, hash, changedAt),
                "パスワードの変更", userId);
        user.setPasswordHash(hash);
        user.setPasswordChangedAt(changedAt);
        log.info("パスワードを変更しました: user={}", user.getUsername());
        record(user, AuditOutcome.SUCCESS, clientIp, null);
        return user;
    }

    /**
     * 再設定用のリンクが今使えるかを調べる。再設定の画面を開いた時点の表示に使う。
     *
     * @param token 再設定用のリンクに載っていた文字列
     * @return 確認結果（招待の確認と同じ形）
     */
    public InvitationCheckResponse checkResetToken(String token) {
        return resetTokenRejection(findByResetToken(token))
                .map(InvitationCheckResponse::rejected)
                .orElseGet(InvitationCheckResponse::allowed);
    }

    /**
     * 再設定用のリンクからパスワードを決め直す。
     *
     * <p>変更時刻も書くので、再設定の前に開いていたその利用者のセッションは次のリクエストで失効する。
     * 購読・視聴済み・Webhook には触れない（削除して招待し直さずに済ませるための機能）。
     *
     * @param token       再設定用のリンクに載っていた文字列
     * @param newPassword 新しいパスワード
     * @param clientIp    操作元の IP。監査ログに残す
     * @throws IllegalArgumentException リンクが使えない、または新しいパスワードが要件を満たさない場合
     */
    public void resetPassword(String token, String newPassword, String clientIp) {
        AppUser user = findByResetToken(token);
        try {
            resetTokenRejection(user).ifPresent(reason -> {
                throw new IllegalArgumentException(reason);
            });
            // 該当する利用者がいなければ resetTokenRejection で断っているので、ここでの user は null ではない
            PasswordPolicy.validate(user.getRole(), newPassword);
            // 読んでから書くまでに同じリンクが使われていたら 0 件になる（1 回限りを UPDATE の条件で守る）
            if (appUserRepository.resetPasswordByToken(token, passwordEncoder.encode(newPassword),
                    changedAtNow(), LocalDateTime.now()) != 1) {
                throw new IllegalArgumentException(RESET_TOKEN_NOT_FOUND);
            }
        } catch (IllegalArgumentException e) {
            // token はログに出さない（知っていればパスワードを決め直せる秘密）
            log.info("パスワードの再設定を受け付けませんでした: user={}, reason={}",
                    user == null ? null : user.getUsername(), e.getMessage());
            // token に該当する利用者がいない失敗は監査ログに書かない。未ログインで誰でも送れるので、
            // 書くと監査ログを際限なく増やせる（未ログインの 401 を記録しないのと同じ理由、#49）。
            // 失敗そのものは GlobalExceptionHandler の WARN に残る
            if (user != null) {
                auditLogger.record(AuditAction.PASSWORD_RESET, AuditOutcome.FAILURE,
                        user.getId(), user.getUsername(), clientIp,
                        AUDIT_TARGET_TYPE, user.getUsername(), e.getMessage());
            }
            throw e;
        }
        log.info("パスワードを再設定しました: user={}", user.getUsername());
        auditLogger.record(AuditAction.PASSWORD_RESET, AuditOutcome.SUCCESS, user.getId(), user.getUsername(),
                clientIp, AUDIT_TARGET_TYPE, user.getUsername(), null);
    }

    /**
     * 再設定用の token から利用者を引く。
     *
     * @param token 再設定用のリンクに載っていた文字列。{@code null} でもよい
     * @return 該当する利用者。無ければ {@code null}
     */
    private AppUser findByResetToken(String token) {
        return token == null ? null : appUserRepository.findByPasswordResetToken(token).orElse(null);
    }

    /**
     * 再設定用のリンクが使えない理由を返す。
     *
     * @param user token で引いた利用者。見つからなければ {@code null}
     * @return 使えない理由。使えるなら {@link Optional#empty()}
     */
    private Optional<String> resetTokenRejection(AppUser user) {
        if (user == null) {
            return Optional.of(RESET_TOKEN_NOT_FOUND);
        }
        if (!LocalDateTime.now().isBefore(user.getPasswordResetExpiresAt())) {
            return Optional.of(RESET_TOKEN_EXPIRED);
        }
        return Optional.empty();
    }

    /**
     * パスワードの変更時刻にする今の時刻。
     *
     * <p>ミリ秒に切り詰める。ナノ秒のままだと DB（H2 の TIMESTAMP は 6 桁）が切り上げて保存することがあり、
     * セッションに持たせた値より DB の値が後になって、変更した本人のセッションまで失効する（実際に発生した）。
     *
     * @return ミリ秒に切り詰めた今の時刻
     */
    private static LocalDateTime changedAtNow() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * 監査ログを 1 件残す。パスワードそのものは書かない。
     *
     * @param user     操作した利用者
     * @param outcome  結果
     * @param clientIp 操作元の IP
     * @param detail   失敗の理由。成功なら {@code null}
     */
    private void record(AppUser user, AuditOutcome outcome, String clientIp, String detail) {
        auditLogger.record(AuditAction.PASSWORD_CHANGE, outcome, user.getId(), user.getUsername(), clientIp,
                AUDIT_TARGET_TYPE, user.getUsername(), detail);
    }
}
