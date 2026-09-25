package com.example.monitor.service;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.util.DatabaseUpdateVerifier;
import com.example.monitor.util.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * ログイン中の利用者が自分のパスワードを変える（#321）。一般利用者も管理者も同じ処理を使う。
 *
 * <p><b>今のパスワードを必ず照合する。</b>ログインしたまま離れた端末を他人に触られても、
 * パスワードを変えてアカウントを乗っ取られないようにするため。
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

    /** 監査ログの対象の種類。利用者管理の監査ログと揃える。 */
    private static final String AUDIT_TARGET_TYPE = "USER";

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogger auditLogger;

    /**
     * パスワードを変える。
     *
     * @param userId          ログイン中の利用者の主キー
     * @param currentPassword 今のパスワード
     * @param newPassword     新しいパスワード
     * @param clientIp        操作元の IP。監査ログに残す
     * @return 変更後の利用者。変更したセッションの主体を差し替えるのに使う
     * @throws IllegalArgumentException 今のパスワードが違う、または新しいパスワードが要件を満たさない場合
     */
    public AppUser changePassword(Long userId, String currentPassword, String newPassword, String clientIp) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("ログイン中の利用者が見つかりません: id=" + userId));
        try {
            if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                throw new IllegalArgumentException("今のパスワードが違います");
            }
            PasswordPolicy.validate(newPassword);
        } catch (IllegalArgumentException e) {
            log.info("パスワードの変更を受け付けませんでした: user={}, reason={}", user.getUsername(), e.getMessage());
            record(user, AuditOutcome.FAILURE, clientIp, e.getMessage());
            throw e;
        }

        String hash = passwordEncoder.encode(newPassword);
        // ミリ秒に切り詰める。ナノ秒のままだと DB（H2 の TIMESTAMP は 6 桁）が切り上げて保存することがあり、
        // セッションに持たせた値より DB の値が後になって、変更した本人のセッションまで失効する（実際に発生した）
        LocalDateTime changedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        DatabaseUpdateVerifier.verify(appUserRepository.updatePassword(userId, hash, changedAt),
                "パスワードの変更", userId);
        user.setPasswordHash(hash);
        user.setPasswordChangedAt(changedAt);
        log.info("パスワードを変更しました: user={}", user.getUsername());
        record(user, AuditOutcome.SUCCESS, clientIp, null);
        return user;
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
