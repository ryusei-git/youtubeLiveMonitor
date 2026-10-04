package com.example.monitor.entity;

/**
 * 監査ログに記録する操作の種別。
 *
 * <p>一覧と記録タイミングの根拠は {@code docs/user-portal-design.md} 4.2。
 * 認証まわり（ログイン・ログアウト・パスワード変更・利用者管理・{@link #ACCESS_DENIED}）は
 * 成功・失敗を問わず<b>全手続き</b>を記録し、それ以外は<b>状態を変える操作</b>だけを対象にする。
 *
 * <p><b>閲覧操作はここに含めない。</b>録画の再生だけで HTTP Range リクエストが数十件飛ぶため、
 * 閲覧まで記録すると監査ログが埋もれて不正の痕跡が見えなくなる（同 4.2 参照）。
 *
 * <p>{@link AuditLog#action} が {@code columnDefinition = "varchar(48)"} を伴うのは、
 * この enum に将来列挙子を追加した際に H2 のネイティブ ENUM 型で全読み書きが壊れないようにするため
 * （{@code Recording.status} で実際に発生した事故と同じ理由。{@code docs/pitfalls.md}「enum の列挙子を増やすと既存 DB で全更新が失敗する」参照）。
 * 監査ログは今後この enum の種類が増えていくことがほぼ確実なテーブルなので、
 * 単純な文字列カラムにしておく効果が特に大きい。
 */
public enum AuditAction {

    /** ログインに成功した。 */
    LOGIN_SUCCESS,

    /** ログインに失敗した。連続発生は総当たり攻撃の兆候。 */
    LOGIN_FAILURE,

    /** ログアウトした。 */
    LOGOUT,

    /** パスワードを変更した（成功・失敗とも記録する）。 */
    PASSWORD_CHANGE,

    /** 管理者がパスワードの再設定用のリンクを発行した。 */
    PASSWORD_RESET_ISSUE,

    /** 再設定用のリンクからパスワードを決め直した（成功・失敗とも記録する）。 */
    PASSWORD_RESET,

    /** 管理者が利用者を新規作成した。 */
    USER_CREATE,

    /** 管理者が利用者を削除した。 */
    USER_DELETE,

    /** 管理者が利用者を無効化した。 */
    USER_DISABLE,

    /** 管理者が無効化した利用者を有効に戻した。 */
    USER_ENABLE,

    /**
     * 管理者が一般利用者の利用者名を変えた（成功・失敗とも記録する）。
     * 成功の {@link AuditLog#detail} には「旧名 → 新名」を書く。対象の列（{@link AuditLog#targetId}）は
     * 変えた後の名前になるので、旧名を残さないと前の名前での記録と結び付けられないため。
     */
    USER_RENAME,

    /**
     * 管理者が一般利用者の新しいパスワードを決めた（成功・失敗とも記録する）。
     * 本人が決め直す {@link #PASSWORD_RESET} と分けるのは、管理者がパスワードを知っている状態になったことを
     * 後から見分けるため。パスワードの値は書かない。
     */
    PASSWORD_SET_BY_ADMIN,

    /** 管理者が招待リンクを発行した。 */
    INVITATION_ISSUE,

    /** 管理者が招待リンクを取り消した。 */
    INVITATION_REVOKE,

    /**
     * ログイン済みで、権限の無いパス（画面・API）へのアクセスを試みた。試行そのものが不正の証跡になる。
     * CSRF トークンの不一致も同じ種別で記録し、{@link AuditLog#detail} に {@code csrf=true} を付けて見分ける
     * （記録する条件は {@code RequestAuthenticationHandler} を参照）。
     */
    ACCESS_DENIED,

    /** チャンネルを監視対象として登録した。 */
    CHANNEL_REGISTER,

    /** チャンネルを監視対象から削除した。 */
    CHANNEL_DELETE,

    /** チャンネルの設定（録画ON/OFF、フィルターなど）を変更した。 */
    CHANNEL_SETTING_CHANGE,

    /** 利用者がチャンネルを購読した。 */
    CHANNEL_SUBSCRIBE,

    /** 利用者がチャンネルの購読を解除した。 */
    CHANNEL_UNSUBSCRIBE,

    /** URL を指定した動画のダウンロードを要求した。 */
    DOWNLOAD_REQUEST,

    /** 録画履歴を削除した。 */
    RECORDING_DELETE,

    /** 通知設定を変更した。 */
    NOTIFICATION_SETTING_CHANGE,

    /** アプリ設定（管理者向け）を変更した。 */
    APP_SETTING_CHANGE
}
