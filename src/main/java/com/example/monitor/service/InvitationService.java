package com.example.monitor.service;

import com.example.monitor.dto.InvitationCheckResponse;
import com.example.monitor.dto.InvitationResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.Invitation;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.InvitationRepository;
import com.example.monitor.util.PasswordPolicy;
import com.example.monitor.util.SecureTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 管理者が発行する招待リンクと、そこからの利用者登録を扱う。
 *
 * <h2>この経路で作られるのは常に一般利用者</h2>
 * <b>権限をリクエストから受け取らない。</b>受け取る形にすると、リクエストを書き換えるだけで
 * 管理者アカウントを作れてしまう。招待は「友達に見てもらう」ための仕組みなので、
 * 管理者を増やす用途には使わせない。
 *
 * <h2>token の扱い</h2>
 * token は<b>知っていること自体が登録の権限になる秘密</b>なので、ログに出さない。
 * 発行・使用のログには招待の主キーと覚え書きだけを残す。
 *
 * <h2>{@code @Profile("!cli")} を付けている理由</h2>
 * 招待は画面からしか使わない。CLI では SecurityConfig ごと無効なので PasswordEncoder も無く、
 * 付け忘れると「Bean が見つからない」で CLI が丸ごと起動できなくなる（実際に発生した）。
 */
@Profile("!cli")
@Service
@RequiredArgsConstructor
@Slf4j
public class InvitationService {

    /** 有効日数の既定値。チャットに貼ったリンクが半永久に生き続けないよう、短めにしてある。 */
    public static final int DEFAULT_VALID_DAYS = 7;

    /** 有効日数の上限。これより長い指定は切り詰める。 */
    private static final int MAX_VALID_DAYS = 90;

    /** 利用者名の最低文字数。 */
    private static final int MIN_USERNAME_LENGTH = 3;

    /** 利用者名の最大文字数（DB の列長と揃えている）。 */
    private static final int MAX_USERNAME_LENGTH = 64;

    /**
     * 利用者名に使わせない文字（制御文字 Cc・書式文字 Cf・空白 Z）。
     *
     * <p>改行を許すと、利用者名を出すすべてのログに偽の行を書き込める。ゼロ幅スペース（Cf）や
     * 全角空白（Z。{@link String#trim()} では前後から落ちない）を許すと、見た目が同じ別の利用者名を作れ、
     * 利用者一覧と監査ログで他人になりすませる。
     */
    private static final Pattern FORBIDDEN_USERNAME_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Z}]");

    /** 使用済みの招待を断るときの文言。 */
    private static final String ALREADY_USED_MESSAGE =
            "この招待リンクは既に使われています。登録済みのアカウントでログインしてください。";

    /** 利用者名が既に使われていて断るときの文言。 */
    private static final String USERNAME_TAKEN_MESSAGE = "この利用者名は既に使われています。別の名前にしてください。";

    private final InvitationRepository invitationRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogger auditLogger;

    /**
     * 招待を発行する。
     *
     * @param label     誰に送るかの覚え書き。空でもよい
     * @param validDays 有効日数。{@code null} や 0 以下なら {@link #DEFAULT_VALID_DAYS}
     * @return 発行した招待
     */
    @Transactional
    public InvitationResponse issue(String label, Integer validDays) {
        int days = validDays == null || validDays <= 0 ? DEFAULT_VALID_DAYS
                : Math.min(validDays, MAX_VALID_DAYS);

        Invitation invitation = new Invitation();
        invitation.setToken(SecureTokens.generate());
        invitation.setLabel(label == null || label.isBlank() ? null : label.trim());
        invitation.setExpiresAt(LocalDateTime.now().plusDays(days));

        Invitation saved = invitationRepository.save(invitation);
        // token は秘密なのでログにも監査ログにも出さない
        log.info("招待を発行しました: id={}, label={}, 有効日数={}", saved.getId(), saved.getLabel(), days);
        recordAdminAction(AuditAction.INVITATION_ISSUE, "INVITATION", String.valueOf(saved.getId()),
                "label=" + saved.getLabel());
        return InvitationResponse.from(saved, LocalDateTime.now());
    }

    /**
     * 招待の一覧を返す（管理者向け）。
     *
     * @return 発行の新しい順の招待
     */
    @Transactional(readOnly = true)
    public List<InvitationResponse> list() {
        LocalDateTime now = LocalDateTime.now();
        return invitationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(invitation -> InvitationResponse.from(invitation, now))
                .toList();
    }

    /**
     * 招待を取り消す。
     *
     * <p>使用済みの招待も履歴として消せるようにしている（誰が使ったかの記録は
     * 利用者アカウント自体が残るため、招待を消しても追跡できなくならない）。
     *
     * @param id 招待の主キー
     * @return 取り消せたら {@code true}。存在しなければ {@code false}
     */
    @Transactional
    public boolean revoke(Long id) {
        if (!invitationRepository.existsById(id)) {
            return false;
        }
        invitationRepository.deleteById(id);
        log.info("招待を取り消しました: id={}", id);
        recordAdminAction(AuditAction.INVITATION_REVOKE, "INVITATION", String.valueOf(id), null);
        return true;
    }

    /**
     * 招待リンクが今使えるかを調べる。登録画面を開いた時点の表示に使う。
     *
     * @param token 招待リンクに載っていた文字列
     * @return 確認結果
     */
    @Transactional(readOnly = true)
    public InvitationCheckResponse check(String token) {
        return invitationRepository.findByToken(token)
                .map(invitation -> rejectionReason(invitation, LocalDateTime.now())
                        .map(InvitationCheckResponse::rejected)
                        .orElseGet(InvitationCheckResponse::allowed))
                .orElseGet(() -> InvitationCheckResponse.rejected(
                        "この招待リンクは見つかりませんでした。URLが途中で切れていないか確認してください。"));
    }

    /**
     * 招待を使って利用者を登録する。
     *
     * <p><b>作られるのは常に一般利用者（{@code USER}）。</b>
     * パスワードは BCrypt でハッシュ化して保存し、平文は残さない
     * （管理者にも本人のパスワードは分からない）。
     *
     * <p><b>1 回限りは UPDATE の条件で守る。</b>未使用の確認と使用済みの書き込みを
     * {@link InvitationRepository#markAccepted} の 1 本にまとめている。同じ招待で同時に登録されると、
     * 読んでから書く 2 段では両方が通るため。利用者名が一意制約に当たったときは例外でトランザクションごと
     * 巻き戻るので、招待は使われないまま残る。
     *
     * @param token    招待リンクに載っていた文字列
     * @param username 希望する利用者名
     * @param password 本人が決めたパスワード
     * @throws IllegalArgumentException 招待が使えない、または入力が要件を満たさない場合
     */
    @Transactional
    public void register(String token, String username, String password) {
        LocalDateTime now = LocalDateTime.now();
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("この招待リンクは使用できません"));

        rejectionReason(invitation, now).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });

        String name = username == null ? "" : username.trim();
        validateUsername(name);
        PasswordPolicy.validate(password);
        // 重い処理を招待の行ロックより前に済ませ、ロックを持つ時間を短くする
        String passwordHash = passwordEncoder.encode(password);

        if (appUserRepository.findByUsername(name).isPresent()) {
            throw new IllegalArgumentException(USERNAME_TAKEN_MESSAGE);
        }

        // 読んでから書くまでに同じ招待が使われていたら 0 件になる（1 回限りを UPDATE の条件で守る）
        if (invitationRepository.markAccepted(invitation.getId(), name, now) != 1) {
            throw new IllegalArgumentException(ALREADY_USED_MESSAGE);
        }

        try {
            appUserRepository.saveAndFlush(new AppUser(name, passwordHash, AppUser.Role.USER));
        } catch (DataIntegrityViolationException e) {
            // 利用者名の確認の後に、別の招待から同じ利用者名が先に登録（コミット）された。
            // 例外で抜けるので招待の使用済みの更新も巻き戻り、招待は未使用に戻る
            throw new IllegalArgumentException(USERNAME_TAKEN_MESSAGE, e);
        }

        log.info("招待から利用者を登録しました: id={}, user={}", invitation.getId(), name);
        // 登録した本人はまだログインしておらず、操作者にあたる第三者もいない
        // （本人が自分自身を登録する経路）。userId・username は空欄になる。
        // clientIp は AuditLogger が要求の接続元で埋める（誰がどこから登録したかを追えるように）
        auditLogger.record(AuditAction.USER_CREATE, AuditOutcome.SUCCESS, null, null, null,
                "USER", name, "招待id=" + invitation.getId());
    }

    /**
     * 招待が使えない理由を返す。
     *
     * @param invitation 招待
     * @param now        判定の基準時刻
     * @return 使えない理由。使えるなら {@link Optional#empty()}
     */
    private Optional<String> rejectionReason(Invitation invitation, LocalDateTime now) {
        if (invitation.getAcceptedAt() != null) {
            return Optional.of(ALREADY_USED_MESSAGE);
        }
        if (!now.isBefore(invitation.getExpiresAt())) {
            return Optional.of("この招待リンクは期限が切れています。管理者に再発行を依頼してください。");
        }
        return Optional.empty();
    }

    /**
     * 利用者名が要件を満たすか調べる。
     *
     * @param username 利用者名
     * @throws IllegalArgumentException 要件を満たさない場合
     */
    private void validateUsername(String username) {
        if (username.length() < MIN_USERNAME_LENGTH || username.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException(
                    "利用者名は" + MIN_USERNAME_LENGTH + "〜" + MAX_USERNAME_LENGTH + "文字にしてください");
        }
        if (FORBIDDEN_USERNAME_CHARACTERS.matcher(username).find()) {
            throw new IllegalArgumentException("利用者名に空白・改行・見えない文字は使えません");
        }
    }

    /**
     * ログイン中の管理者による操作として監査ログを1件記録する。
     *
     * <p>招待の発行・取消は必ずログイン済みの管理者が行う。操作者と操作元の IP は
     * {@link AuditLogger#recordByCurrentUser} がログイン中の主体と要求から埋める。
     *
     * @param action     操作の種別
     * @param targetType 操作対象の種類
     * @param targetId   操作対象の識別子
     * @param detail     補足の説明。無ければ {@code null}
     */
    private void recordAdminAction(AuditAction action, String targetType, String targetId, String detail) {
        auditLogger.recordByCurrentUser(action, AuditOutcome.SUCCESS, targetType, targetId, detail);
    }
}
