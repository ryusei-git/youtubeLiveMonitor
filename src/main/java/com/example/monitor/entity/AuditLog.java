package com.example.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 状態変更操作・認証手続きの証跡として残す監査ログ 1 件。
 *
 * <p>設計の根拠は {@code docs/user-portal-design.md} 4 章。以下は本クラス固有の注意点。
 *
 * <h2>{@link #userId} に外部キーを張らない理由</h2>
 * {@link Recording#channel} や {@link NotificationHistory#channel} が
 * {@code ON DELETE CASCADE} で連鎖削除されるのとは<b>意図的に逆の方針</b>を採る。
 * 監査ログは「誰が何をしたか」の証跡であり、対象の利用者を削除した瞬間に
 * その利用者が行った操作の記録まで消えてしまっては証跡として成立しない。
 * そのため {@link #userId} は素の {@code Long} の値として持つだけで、
 * {@code @ManyToOne} も外部キー制約も付けない。
 *
 * <p>同じ理由で {@link #username} は {@link AppUser} への参照ではなく、
 * <b>記録時点の値をそのまま複写</b>して持つ。利用者が後から削除・改名されても、
 * ログを読んだときに「誰の操作だったか」が分かるようにするため。
 *
 * <h2>更新・削除ができないこと</h2>
 * 書き換えられる証跡は証跡ではないため、このエンティティは<b>追記専用</b>として扱う。
 * {@link com.example.monitor.repository.AuditLogRepository} には更新・削除系のメソッドを
 * 定義していない（理由は同クラスの JavaDoc 参照）。このクラスに Lombok の {@code @Setter}
 * を付けていないのも同じ理由で、一度組み立てた内容を後から書き換える経路をコード上にも
 * 作らないようにしている（必要な値は {@link Builder} で最初にすべて揃えて生成する）。
 *
 * <h2>enum 列の {@code columnDefinition}</h2>
 * {@link #action} と {@link #outcome} はどちらも {@code columnDefinition} を明示している。
 * 付けないと H2 のネイティブ ENUM 型になり、後から列挙子を追加した瞬間にその列の
 * 全読み書きが壊れる（{@code Recording.status} で実際に発生した。{@code docs/pitfalls.md}「enum の列挙子を増やすと既存 DB で全更新が失敗する」参照）。
 * 監査ログは今後 {@link AuditAction} の種類が増えていくことが確実なテーブルなので、
 * この対策は特に重要。
 */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    /** このテーブルの主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 操作が発生した時刻。
     *
     * <p>呼び出し側が渡した値ではなく、{@link #applyOccurredAtOnInsert()} で
     * INSERT 直前の時刻を必ず上書きする（他の {@code *At} フィールドと同じ作法。
     * {@link Recording#startedAt} 等を参照）。証跡である以上、記録者が任意の時刻を
     * 詐称できないようにする意味でも、この値を呼び出し側から自由に設定させないことが望ましい。
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    /**
     * リクエストごとの相関ID。同一リクエスト内のアプリログ・アクセスログと突き合わせるために使う
     * （{@code docs/user-portal-design.md} 1.3, 4.6 参照）。{@code RequestTracingFilter} が発行した ID。
     * フィルターを通らない経路（起動時の処理など）では {@code null}。
     */
    @Column(name = "request_id", length = 36)
    private String requestId;

    /**
     * 操作を行った利用者の主キー。未ログインでの操作（ログイン失敗など）では {@code null}。
     *
     * <p>外部キーは張らない。理由はクラス JavaDoc 参照。
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 操作を行った利用者名。{@link AppUser#username} への参照ではなく、
     * 記録時点の値の複写（クラス JavaDoc 参照）。未ログインでの操作では {@code null}。
     */
    @Column(length = 64)
    private String username;

    /** 操作元のクライアントIP。IPv6 表記も入る長さ（45文字）を確保している。 */
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    /**
     * 操作の種別。
     *
     * <p>{@code columnDefinition} の理由はクラス JavaDoc 参照。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48, columnDefinition = "varchar(48)")
    private AuditAction action;

    /**
     * 操作対象の種類（{@code "CHANNEL"} / {@code "RECORDING"} など）。
     *
     * <p>固定の enum にしていないのは、対象の種類が {@link AuditAction} と同じペースで
     * 増えていく自由記述であり、記録する側がその都度、意味の分かる文字列を渡す方が
     * 都度 enum を拡張するより素直なため。
     */
    @Column(name = "target_type", length = 32)
    private String targetType;

    /** 操作対象の識別子（チャンネルID・録画IDなど）。 */
    @Column(name = "target_id", length = 128)
    private String targetId;

    /**
     * 操作の結果。
     *
     * <p>{@code columnDefinition} の理由はクラス JavaDoc 参照。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private AuditOutcome outcome;

    /** 失敗理由など、補足の説明。成功時は基本的に {@code null}。 */
    @Column(length = 1000)
    private String detail;

    /** 発生時刻を自動設定する。JPA が INSERT 直前に呼び出す。 */
    @PrePersist
    void applyOccurredAtOnInsert() {
        this.occurredAt = LocalDateTime.now();
    }
}
