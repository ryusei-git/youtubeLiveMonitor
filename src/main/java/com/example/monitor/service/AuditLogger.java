package com.example.monitor.service;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditLog;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.repository.AuditLogRepository;
import com.example.monitor.util.RequestContext;
import com.example.monitor.util.TextTruncator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 監査ログへの記録窓口。認証イベント・状態変更操作の両方から共通で使う。
 *
 * <h2>記録の失敗を呼び出し元へ伝播させない</h2>
 * 監査ログは証跡であって、業務そのものを止めるためのものではない。
 * DB 障害等で {@link AuditLogRepository#save} が失敗しても、<b>ログインや操作自体は
 * 通常どおり進める</b>。ここで例外を投げ返すと、監査ログの不調がそのまま
 * サービス停止に直結してしまう（記録できないことより、記録できないせいで
 * 誰もログインできなくなることのほうが実害が大きい）。失敗はアプリログに
 * WARN で残し、次の調査につなげる。
 *
 * <p><b>ただし、呼び出し元の {@code @Transactional} の中で呼ばれたときは、この約束を守れない。</b>
 * {@code save} は呼び出し元のトランザクションに参加するので、そこで例外が出るとトランザクション全体に
 * 巻き戻しの印が付く。ここで握りつぶしても、呼び出し元のコミットが {@code UnexpectedRollbackException}
 * で失敗する（購読の変更・招待・利用者の管理がこの形）。それでも記録を別のトランザクション
 * （{@code REQUIRES_NEW}）に分けないのは、次の 2 つの理由から。
 * <ul>
 *   <li>業務が後から巻き戻っても「成功」の記録だけが残り、起きていない操作の証跡になる</li>
 *   <li>1 つの要求が DB の接続を 2 本使う。接続は 5 本に絞っていて（application.yml の hikari）、
 *       同時の要求で取り合いになる</li>
 * </ul>
 * 代わりに、入力が原因で save が失敗する経路（列の長さの超過）をこのクラスで先に潰す（下記）。
 * 残るのは DB そのものの障害で、そのときは業務の保存も失敗するので、監査だけを守っても意味が無い。
 *
 * <h2>相関 ID と操作元の IP はここで必ず埋める</h2>
 * 呼び出し側に {@link RequestContext} を意識させると、一部の呼び出し元だけ
 * 値が抜ける事故が起きやすい。窓口をここ一箇所にまとめ、
 * {@link AuditLog#requestId} は常にこのクラスが設定する。
 * 操作元の IP も、呼び出し側が {@code null} を渡したら、処理中の要求の接続元で補う
 * （以前は呼び出し側に渡させていて、認証の手続きとパスワードの変更以外では空になっていた）。
 * 操作者は {@link #recordByCurrentUser} を使えば、ログイン中の主体から埋まる。
 *
 * <h2>列の長さに切ってから保存する</h2>
 * ログイン失敗の利用者名（未ログインの人が決めるフォームの値）、権限外アクセスの対象（要求の URI）、
 * ダウンロードの URL は、長さに上限が無い。列の長さを超えると INSERT ごと失敗し、総当たりや権限外の
 * 試行という、いちばん残したい証跡が消える。そのため各列の長さ（{@link AuditLog} の {@code @Column}
 * の {@code length}）に切り、切ったことが分かるよう末尾を「…」にする。列を広げないのは、
 * 上限の無い入力にはどこまで広げても追いつかないうえ、広げると次の起動で {@code ddl-auto: update} が
 * 本番 DB の列の定義を黙って変えるため。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogger {

    /** 列の長さに収まらない値を切ったことを示す印。 */
    private static final String TRUNCATION_MARK = "…";

    // 各列の長さ。AuditLog の @Column の length と同じ値にする（列の長さを変えるときは両方を直す）
    private static final int USERNAME_LENGTH = 64;
    private static final int CLIENT_IP_LENGTH = 45;
    private static final int TARGET_TYPE_LENGTH = 32;
    private static final int TARGET_ID_LENGTH = 128;
    private static final int DETAIL_LENGTH = 1000;

    private final AuditLogRepository repository;

    /**
     * 監査ログを1件記録する。
     *
     * @param action     操作の種別
     * @param outcome    操作の結果
     * @param userId     操作を行った利用者の主キー。未ログインでの操作（ログイン失敗など）では {@code null}
     * @param username   操作を行った利用者名。未ログインでの操作では {@code null}
     * @param clientIp   操作元のクライアントIP。{@code null} なら処理中の HTTP 要求の接続元で補う（要求の外なら空欄のまま）
     * @param targetType 操作対象の種類。対象が無い操作（ログイン等）では {@code null}
     * @param targetId   操作対象の識別子。対象が無い操作では {@code null}
     * @param detail     補足の説明。無ければ {@code null}
     */
    public void record(AuditAction action, AuditOutcome outcome, Long userId, String username,
                       String clientIp, String targetType, String targetId, String detail) {
        try {
            // 呼び出し側が IP を渡さなければ、処理中の要求の接続元で補う（要求の外なら null のまま）
            String ip = clientIp != null ? clientIp : RequestContext.currentClientIp();
            repository.save(AuditLog.builder()
                    .requestId(RequestContext.currentRequestId())
                    .userId(userId)
                    .username(fit(username, USERNAME_LENGTH))
                    .clientIp(fit(ip, CLIENT_IP_LENGTH))
                    .action(action)
                    .outcome(outcome)
                    .targetType(fit(targetType, TARGET_TYPE_LENGTH))
                    .targetId(fit(targetId, TARGET_ID_LENGTH))
                    .detail(fit(detail, DETAIL_LENGTH))
                    .build());
        } catch (Exception e) {
            log.warn("監査ログの記録に失敗しました: action={}, outcome={}, user={}", action, outcome, username, e);
        }
    }

    /**
     * 対象を伴わない操作（ログイン・ログアウトなど）を記録する。
     *
     * @param action   操作の種別
     * @param outcome  操作の結果
     * @param userId   操作を行った利用者の主キー。未ログインでの操作では {@code null}
     * @param username 操作を行った利用者名。未ログインでの操作では {@code null}
     * @param clientIp 操作元のクライアントIP。{@code null} なら処理中の HTTP 要求の接続元で補う（要求の外なら空欄のまま）
     * @param detail   補足の説明。無ければ {@code null}
     */
    public void recordAuthEvent(AuditAction action, AuditOutcome outcome, Long userId, String username,
                                String clientIp, String detail) {
        record(action, outcome, userId, username, clientIp, null, null, detail);
    }

    /**
     * ログイン中の利用者の操作として、監査ログを 1 件記録する。操作者と操作元の IP は、このメソッドが埋める。
     *
     * <p>操作者は、ログイン時に主体へ載せた ID と名前を使い、名前から DB を引き直さない
     * （{@link RequestContext#currentUserId()} 参照）。以前は「名前 → findByUsername → ID」の 3 行が
     * サービスごとに複製されていて、IP も渡されずに空になっていた。
     *
     * <p>CLI・監視ループ・録画スレッドのように、ログインも HTTP の要求も無い経路から呼ばれた場合は、
     * 利用者と IP を空欄のまま記録する。
     *
     * @param action     操作の種別
     * @param outcome    操作の結果
     * @param targetType 操作対象の種類
     * @param targetId   操作対象の識別子
     * @param detail     補足の説明。無ければ {@code null}
     */
    public void recordByCurrentUser(AuditAction action, AuditOutcome outcome,
                                    String targetType, String targetId, String detail) {
        record(action, outcome, RequestContext.currentUserId(), RequestContext.currentUsername(),
                null, targetType, targetId, detail);
    }

    /**
     * 列の長さに収まらない値を、末尾を「…」にして収める。
     *
     * @param value     記録する値
     * @param maxLength 列の長さ
     * @return 収まっていれば {@code value} そのもの（{@code null} なら {@code null}）
     */
    private static String fit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return TextTruncator.truncate(value, maxLength - TRUNCATION_MARK.length()) + TRUNCATION_MARK;
    }
}
