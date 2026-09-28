package com.example.monitor.service;

import com.example.monitor.dto.TableDataResponse;
import com.example.monitor.dto.TableSummary;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.util.CaseInsensitiveMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DB のテーブルを表形式で閲覧・編集するための機能。H2 標準コンソールの代替。
 *
 * <h2>SQL インジェクションへの対策</h2>
 * テーブル名やカラム名は SQL のプレースホルダ（{@code ?}）で渡せないため、
 * どうしても SQL 文字列に直接埋め込む必要がある。そこで
 * <b>埋め込む前に必ず {@link DatabaseMetaData} から取得した実在のスキーマ情報と照合し、
 * 一致したもの（DB が返した文字列そのもの）だけを使う</b>。
 * 利用者から渡された文字列をそのまま SQL に混ぜることはない。
 * 値については通常どおりプレースホルダで渡す。
 *
 * <h2>監査ログ</h2>
 * 行の更新（{@link #updateRow}）は、成功・失敗とも監査ログに残す。任意のテーブルの任意の列を書き換えられる、
 * 管理者の操作の中で最も強い操作なので、セッションを奪われたとき・操作を誤ったときに、
 * 誰がいつどの行を変えたかを追えるようにするため。閲覧（{@link #getTableData}）は残さない
 * （{@code docs/user-portal-design.md} 4.2。閲覧まで残すと不正の痕跡が埋もれる）。
 *
 * <p>種別は設定画面の保存と同じ {@link AuditAction#APP_SETTING_CHANGE} にし、対象の種類 {@code DB_ROW}、
 * 対象の識別子「テーブル名/主キー値」で見分ける。専用の種別を足すと、監査ログ画面の絞り込みの選択肢と
 * 表示名も直すことになる。一方で管理者の直接操作は件数が少なく、同じ種別の中で対象の種類を見れば足りる。
 *
 * <p>残すのは列名だけで、値は残さない。この画面は {@link #EXCLUDED_TABLES} に無い全部の表を対象にするので、
 * 秘密の列を持つ表を足して除外し忘れると、その値まで編集でき、監査ログに写ってしまう。
 * 監査ログは追記専用で後から消せない。失敗の理由の扱いは {@link #failureReason} 参照。
 *
 * <h2>想定する利用範囲</h2>
 * 認証を持たない個人用のローカルツールという前提で作られている。
 * 任意のテーブルを書き換えられるため、外部に公開する場合は必ずアクセス制御を追加すること。
 */
@Service
@RequiredArgsConstructor
public class DatabaseTableService {

    /** 対象とするスキーマ。H2 の既定スキーマ。 */
    private static final String TARGET_SCHEMA = "PUBLIC";

    /** 監査ログに残す操作対象の種類。設定画面の保存（{@code ENV_FILE}）と見分けるため。 */
    private static final String AUDIT_TARGET_TYPE = "DB_ROW";

    /**
     * 認証情報の露出や証跡の改変を防ぐため、汎用閲覧・編集の対象から除外するテーブル。
     *
     * <p><b>{@code AUDIT_LOGS}（監査ログ）は一覧にも出さない。</b>
     * この画面はセルをダブルクリックして直接編集できるため、対象に含めると証跡が
     * 改ざん可能になってしまう（{@code docs/user-portal-design.md} 1.4, 4.1 参照。
     * 書き換えられる証跡は証跡ではない）。{@link #listTableNames()} の時点で除外しているため、
     * {@link #resolveExistingTableName(String)} を経由する {@link #getTableData} と
     * {@link #updateRow} も自動的にこのテーブルを「存在しない」ものとして扱う
     * （{@link IllegalArgumentException} になる）。
     *
     * <p>{@code INVITATIONS} も同じ理由で除外している。招待の token は
     * <b>知っていること自体が利用者登録の権限になる秘密</b>で、招待画面では
     * 使用済み・期限切れのものを返さないようにしている。ここから生の値を
     * 一覧できてしまうと、その配慮が意味を失う。
     */
    private static final Set<String> EXCLUDED_TABLES = Set.of("AUDIT_LOGS", "APP_USERS", "INVITATIONS");

    /**
     * テーブルの論理名（日本語）の対応表。
     *
     * <p>DB管理画面は本来「任意のテーブルを見られる」ことを目的にしているため、
     * ここに無い（今後追加される）テーブルも動作は壊さない。その場合は
     * {@link #tableLabel(String)} が物理名をそのまま返す。
     */
    private static final Map<String, String> TABLE_LABELS = Map.of(
            "CHANNELS", "チャンネル",
            "NOTIFICATION_HISTORY", "通知履歴",
            "RECORDINGS", "録画履歴",
            "APP_USERS", "ログイン利用者",
            "USER_SUBSCRIPTIONS", "利用者の購読",
            "INVITATIONS", "招待"
    );

    /**
     * カラムの論理名（日本語）の対応表。テーブル名 → （物理名 → 論理名）。
     *
     * <p>物理名はエンティティのフィールド名から Hibernate の既定命名規則
     * （キャメルケース→スネークケース）で決まる。日本人管理者が読みやすいよう、
     * ここで日本語の論理名に対応付けている。
     */
    private static final Map<String, Map<String, String>> COLUMN_LABELS = Map.of(
            "CHANNELS", Map.ofEntries(
                    Map.entry("ID", "ID"),
                    Map.entry("YOUTUBE_CHANNEL_ID", "チャンネルID"),
                    Map.entry("CHANNEL_NAME", "チャンネル名"),
                    Map.entry("LAST_NOTIFIED_VIDEO_ID", "最終通知動画ID"),
                    Map.entry("CURRENTLY_LIVE", "配信中フラグ"),
                    Map.entry("CURRENT_LIVE_VIDEO_ID", "配信中の動画ID"),
                    Map.entry("LAST_CHECKED_AT", "最終チェック日時"),
                    Map.entry("RECORD_ENABLED", "自動録画フラグ"),
                    Map.entry("LAST_RECORDED_VIDEO_ID", "最終録画動画ID"),
                    Map.entry("RECORD_TITLE_KEYWORDS", "録画タイトルフィルター"),
                    Map.entry("CREATED_AT", "登録日時")
            ),
            "NOTIFICATION_HISTORY", Map.of(
                    "ID", "ID",
                    "CHANNEL_ID", "チャンネル（内部ID）",
                    "VIDEO_ID", "動画ID",
                    "VIDEO_TITLE", "配信タイトル",
                    "STATUS", "送信結果",
                    "ERROR_MESSAGE", "エラー内容",
                    "NOTIFIED_AT", "通知日時"
            ),
            "RECORDINGS", Map.of(
                    "ID", "ID",
                    "CHANNEL_ID", "チャンネル（内部ID）",
                    "VIDEO_ID", "動画ID",
                    "VIDEO_TITLE", "配信タイトル",
                    "FILE_PATH", "ファイルパス",
                    "FILE_SIZE_BYTES", "ファイルサイズ（バイト）",
                    "STATUS", "状態",
                    "STARTED_AT", "開始日時",
                    "COMPLETED_AT", "完了日時"
            ),
            "APP_USERS", Map.of(
                    "ID", "ID",
                    "USERNAME", "ユーザー名",
                    "PASSWORD_HASH", "パスワードハッシュ",
                    "ROLE", "権限",
                    "ENABLED", "有効フラグ",
                    "CREATED_AT", "作成日時",
                    "LAST_LOGIN_AT", "最終ログイン日時"
            )
    );

    /**
     * 行を「カラム名 → 値」にする変換。バイナリの列だけは中身ではなく「（バイナリ n バイト）」の文字にする。
     *
     * <p>既定の変換（{@link ColumnMapRowMapper}）は BLOB を中身ごと {@code byte[]} に読み、それが
     * Base64 の JSON になってセルに出ていた。サムネイルの表（1 枚最大 2MB）では 1 ページで数十 MB を
     * 読んで送るのに、画面には読めない文字列が並ぶだけだった（#184）。H2 の BLOB は長さを持っているので、
     * {@link Blob#length()} は中身を読まずに長さを返す（H2 2.2 で確認）。
     *
     * <p>列の型ではなく値の型で見分けるのは、H2 が UUID の列も JDBC の型では {@code BINARY} と報告するため
     * （値は {@link java.util.UUID} で返り、今までどおり表示される）。
     */
    private static final ColumnMapRowMapper BINARY_AS_LENGTH_ROW_MAPPER = new ColumnMapRowMapper() {
        @Override
        protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
            Object value = rs.getObject(index);
            if (value instanceof Blob blob) {
                return binaryLabel(blob.length());
            }
            if (value instanceof byte[] bytes) {
                return binaryLabel(bytes.length);
            }
            return super.getColumnValue(rs, index);
        }
    };

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogger auditLogger;

    /**
     * 選択できるテーブルの一覧を、論理名（日本語）付きで返す。
     *
     * @return テーブル名と論理名の一覧
     */
    public List<TableSummary> listTableSummaries() {
        return listTableNames().stream()
                .map(tableName -> new TableSummary(tableName, tableLabel(tableName)))
                .toList();
    }

    /**
     * 閲覧できるテーブル名の一覧を返す。
     *
     * <p>{@link #EXCLUDED_TABLES} に含まれるテーブル（監査ログ）はここで除外する。
     * この画面全体のテーブル解決が {@link #resolveExistingTableName(String)} を経由して
     * この一覧に照合する作りになっているため、ここで除いておけば閲覧・編集の両方から
     * 自動的に対象外になる。
     *
     * @return テーブル名の一覧
     * @throws IllegalStateException メタ情報の取得に失敗した場合
     */
    public List<String> listTableNames() {
        List<String> tableNames = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData()
                     .getTables(null, TARGET_SCHEMA, "%", new String[]{"TABLE"})) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                if (!EXCLUDED_TABLES.contains(tableName)) {
                    tableNames.add(tableName);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("テーブル一覧の取得に失敗しました", e);
        }
        return tableNames;
    }

    /**
     * 指定テーブルの内容をページ単位で取得する。
     *
     * <p>バイナリの列は中身ではなく大きさの文字で返す（{@link #BINARY_AS_LENGTH_ROW_MAPPER}）。
     * 画面がその列を編集させないよう、どの列がバイナリかも添える（{@link #findBinaryColumns}）。
     *
     * @param requestedTableName 取得したいテーブル名（大文字小文字は区別しない）
     * @param page               ページ番号（0 始まり）
     * @param size               1 ページあたりの行数
     * @return テーブルの内容とカラム構成
     * @throws IllegalArgumentException 存在しないテーブル名を指定した場合
     */
    public TableDataResponse getTableData(String requestedTableName, int page, int size) {
        String tableName = resolveExistingTableName(requestedTableName);
        List<String> columnNames = fetchColumnNames(tableName);
        String primaryKeyColumn = findPrimaryKeyColumn(tableName).orElse(null);

        Long totalRowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT * FROM " + tableName + " LIMIT ? OFFSET ?", BINARY_AS_LENGTH_ROW_MAPPER, size, page * size);

        return new TableDataResponse(
                tableName,
                tableLabel(tableName),
                columnNames,
                columnLabels(tableName, columnNames),
                primaryKeyColumn,
                findBinaryColumns(tableName),
                rows,
                totalRowCount == null ? 0 : totalRowCount,
                page,
                size);
    }

    /**
     * テーブルの論理名（日本語）を返す。対応表に無いテーブルは物理名をそのまま返す
     * （画面側が必ず何らかの表示文字列を得られるようにするため）。
     *
     * @param tableName 対象テーブル名
     * @return 論理名。対応表に無ければ {@code tableName} そのもの
     */
    private String tableLabel(String tableName) {
        return TABLE_LABELS.getOrDefault(tableName, tableName);
    }

    /**
     * カラムごとの論理名（日本語）の対応を組み立てる。対応表に無いカラムは物理名をそのまま使う。
     *
     * @param tableName   対象テーブル名
     * @param columnNames 対象テーブルの全カラム名
     * @return カラム名（物理名） → 論理名の対応。すべてのカラムについて必ず値を持つ
     */
    private Map<String, String> columnLabels(String tableName, List<String> columnNames) {
        Map<String, String> knownLabels = COLUMN_LABELS.getOrDefault(tableName, Map.of());
        Map<String, String> labels = new LinkedHashMap<>();
        for (String columnName : columnNames) {
            labels.put(columnName, knownLabels.getOrDefault(columnName, columnName));
        }
        return labels;
    }

    /**
     * バイナリの列のセルに出す文字。中身の代わりに大きさだけ見せる（空か、どのくらいの大きさかは分かる）。
     *
     * @param length バイト数
     * @return 「（バイナリ 12,345 バイト）」の形の文字
     */
    private static String binaryLabel(long length) {
        return String.format(Locale.ROOT, "（バイナリ %,d バイト）", length);
    }

    /**
     * 主キーで 1 行を特定し、指定されたカラムだけを更新する。
     *
     * <p>実在しないカラム名と主キー自体の変更指示は黙って無視する。
     * 画面から不要なフィールドが一緒に送られてきても失敗させないためで、
     * 主キーを守るのは行の同一性を壊さないため。
     *
     * <p>バイナリの列（{@link #findBinaryColumns}）への更新は無視せず断る。受け取るのは文字列なので、
     * 書き込むと入力した文字の UTF-8 がそのまま BLOB に入り、サムネイルの画像などが壊れる（#207）。
     *
     * <p>成功・失敗とも監査ログに残す（クラスの JavaDoc「監査ログ」参照）。
     *
     * @param requestedTableName 更新対象のテーブル名
     * @param primaryKeyValue    更新する行の主キー値（文字列。数値型の主キーには自動変換される）
     * @param requestedChanges   「カラム名 → 新しい値」の対応
     * @throws IllegalArgumentException テーブルが存在しない、更新できる項目が 1 つもない、
     *                                  バイナリの列を含む、該当する行がない、
     *                                  または値が列の型・長さ・制約に合わず DB が受け付けない場合
     * @throws IllegalStateException    対象テーブルに主キーがない場合
     */
    public void updateRow(String requestedTableName, String primaryKeyValue, Map<String, Object> requestedChanges) {
        String targetId = requestedTableName + "/" + primaryKeyValue;
        List<String> updatedColumns;
        try {
            updatedColumns = applyRowUpdate(requestedTableName, primaryKeyValue, requestedChanges);
        } catch (RuntimeException e) {
            auditLogger.recordByCurrentUser(AuditAction.APP_SETTING_CHANGE, AuditOutcome.FAILURE,
                    AUDIT_TARGET_TYPE, targetId, "reason=" + failureReason(e));
            throw e;
        }
        auditLogger.recordByCurrentUser(AuditAction.APP_SETTING_CHANGE, AuditOutcome.SUCCESS,
                AUDIT_TARGET_TYPE, targetId, "columns=" + String.join(",", updatedColumns));
    }

    /**
     * {@link #updateRow} の本体。監査ログの記録と分けるために切り出している。
     *
     * @param requestedTableName 更新対象のテーブル名
     * @param primaryKeyValue    更新する行の主キー値
     * @param requestedChanges   「カラム名 → 新しい値」の対応
     * @return 実際に更新したカラム名（DB 上の正式な表記）
     */
    private List<String> applyRowUpdate(String requestedTableName, String primaryKeyValue,
                                        Map<String, Object> requestedChanges) {
        String tableName = resolveExistingTableName(requestedTableName);
        String primaryKeyColumn = findPrimaryKeyColumn(tableName)
                .orElseThrow(() -> new IllegalStateException("主キーが存在しないため更新できません: " + tableName));

        Map<String, Object> applicableChanges = filterUpdatableColumns(tableName, primaryKeyColumn, requestedChanges);
        if (applicableChanges.isEmpty()) {
            throw new IllegalArgumentException("更新できる項目がありません");
        }
        List<String> binaryColumns = findBinaryColumns(tableName);
        for (String columnName : applicableChanges.keySet()) {
            if (binaryColumns.contains(columnName)) {
                throw new IllegalArgumentException("バイナリの列は編集できません: " + columnName);
            }
        }

        String setClause = applicableChanges.keySet().stream()
                .map(columnName -> columnName + " = ?")
                .collect(Collectors.joining(", "));

        List<Object> parameters = new ArrayList<>(applicableChanges.values());
        parameters.add(convertPrimaryKeyValue(tableName, primaryKeyColumn, primaryKeyValue));

        String sql = "UPDATE " + tableName + " SET " + setClause + " WHERE " + primaryKeyColumn + " = ?";
        int updatedRowCount;
        try {
            updatedRowCount = jdbcTemplate.update(sql, parameters.toArray());
        } catch (DataIntegrityViolationException e) {
            // 画面は値を常に文字列で送るので、数値・日時の列に読めない文字や空欄を入れるとここに来る。
            // 管理者の入力の誤りなので 400 にし、見直す列を返す。DB の文面は SQL と入力値を含むので返さない
            throw new IllegalArgumentException("値を保存できません。列の型・長さ・空欄の可否・重複を確かめてください: "
                    + String.join(", ", applicableChanges.keySet()), e);
        }

        if (updatedRowCount == 0) {
            throw new IllegalArgumentException(
                    "該当する行が見つかりません: " + primaryKeyColumn + "=" + primaryKeyValue);
        }
        return List.copyOf(applicableChanges.keySet());
    }

    /**
     * 更新の失敗を監査ログに残すときの理由。
     *
     * <p>このクラスが自分で投げる例外（{@link IllegalArgumentException}・{@link IllegalStateException}）は、
     * 列に書き込む値を文言に含めないので、文言をそのまま使う。DB が値を受け付けなかった場合（型の変換や
     * 列の長さの超過など）も、{@link #applyRowUpdate} が列名だけの {@link IllegalArgumentException} に
     * 置き換えてからここへ来る。それ以外（ロック待ちの時間切れや接続の失敗で JDBC が投げる
     * {@code DataAccessException} など）は、例外の種類名だけにする。DB の文言には SQL 文や入力した値が
     * 入ることがあり、値を残さない方針（クラスの JavaDoc「監査ログ」）が崩れるため。
     *
     * @param e 更新中に起きた例外
     * @return 監査ログの {@code detail} に載せる理由
     */
    private static String failureReason(RuntimeException e) {
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    /**
     * 更新指示のうち、実際に更新してよいものだけを残す。
     *
     * @param tableName        対象テーブル名
     * @param primaryKeyColumn 主キーのカラム名
     * @param requestedChanges 画面から渡された更新指示
     * @return 実在するカラムかつ主キー以外のものだけを残した更新指示
     */
    private Map<String, Object> filterUpdatableColumns(String tableName, String primaryKeyColumn,
                                                       Map<String, Object> requestedChanges) {
        List<String> existingColumns = fetchColumnNames(tableName);
        Map<String, Object> updatableChanges = new LinkedHashMap<>();

        for (Map.Entry<String, Object> change : requestedChanges.entrySet()) {
            String requestedColumn = change.getKey();
            boolean isPrimaryKey = requestedColumn.equalsIgnoreCase(primaryKeyColumn);

            // SQL に埋め込むのは利用者が送った文字列ではなく、DB から取得した正式なカラム名
            CaseInsensitiveMatcher.findIgnoreCase(existingColumns, requestedColumn)
                    .filter(actualColumnName -> !isPrimaryKey)
                    .ifPresent(actualColumnName -> updatableChanges.put(actualColumnName, change.getValue()));
        }
        return updatableChanges;
    }

    /**
     * 指定された名前のテーブルが実在するか確認し、DB 上の正式な表記を返す。
     *
     * <p>SQL に埋め込んでよい文字列はこのメソッドの戻り値だけ。
     * 利用者が渡した文字列をそのまま使ってはならない。
     *
     * @param requestedTableName 利用者が指定したテーブル名
     * @return DB 上の正式なテーブル名
     * @throws IllegalArgumentException 実在しないテーブル名だった場合
     */
    private String resolveExistingTableName(String requestedTableName) {
        return CaseInsensitiveMatcher.findIgnoreCase(listTableNames(), requestedTableName)
                .orElseThrow(() -> new IllegalArgumentException("存在しないテーブルです: " + requestedTableName));
    }

    /**
     * テーブルのカラム名を定義順に取得する。
     *
     * @param tableName 対象テーブル名（実在確認済みのもの）
     * @return カラム名の一覧
     * @throws IllegalStateException メタ情報の取得に失敗した場合
     */
    private List<String> fetchColumnNames(String tableName) {
        List<String> columnNames = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getColumns(null, TARGET_SCHEMA, tableName, "%")) {
            while (resultSet.next()) {
                columnNames.add(resultSet.getString("COLUMN_NAME"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("カラム一覧の取得に失敗しました: " + tableName, e);
        }
        return columnNames;
    }

    /**
     * バイナリの列（セルに「（バイナリ n バイト）」の文字を出す列）の名前を返す。
     *
     * <p>{@link #BINARY_AS_LENGTH_ROW_MAPPER} と同じく値の型で見分けるので、表示が「（バイナリ n バイト）」になる列と
     * 一致する（UUID の列は入らない）。{@link ResultSetMetaData#getColumnClassName(int)} は {@code getObject} が返す値の
     * 型なので、値が NULL の行や行の無い表でも分かる（セルの値で見ると、NULL のセルには文字を書き込めてしまう）。
     * {@code WHERE 1 = 0} で行は読まない。
     *
     * @param tableName 対象テーブル名（実在確認済みのもの）
     * @return バイナリの列の名前
     */
    private List<String> findBinaryColumns(String tableName) {
        return jdbcTemplate.query("SELECT * FROM " + tableName + " WHERE 1 = 0", resultSet -> {
            ResultSetMetaData metaData = resultSet.getMetaData();
            List<String> binaryColumns = new ArrayList<>();
            for (int index = 1; index <= metaData.getColumnCount(); index++) {
                String className = metaData.getColumnClassName(index);
                if (Blob.class.getName().equals(className) || byte[].class.getName().equals(className)) {
                    binaryColumns.add(metaData.getColumnName(index));
                }
            }
            return binaryColumns;
        });
    }

    /**
     * テーブルの主キーのカラム名を取得する。複合主キーの場合は先頭の 1 つだけを返す。
     *
     * @param tableName 対象テーブル名（実在確認済みのもの）
     * @return 主キーのカラム名。主キーがなければ {@link Optional#empty()}
     * @throws IllegalStateException メタ情報の取得に失敗した場合
     */
    private Optional<String> findPrimaryKeyColumn(String tableName) {
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getPrimaryKeys(null, TARGET_SCHEMA, tableName)) {
            if (resultSet.next()) {
                return Optional.of(resultSet.getString("COLUMN_NAME"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("主キー情報の取得に失敗しました: " + tableName, e);
        }
        return Optional.empty();
    }

    /**
     * URL のパスから文字列として受け取った主キー値を、カラムの型に合わせて変換する。
     *
     * <p>主キーが数値型のときに文字列のまま渡すと型の不一致で行が見つからないため。
     *
     * @param tableName        対象テーブル名（実在確認済みのもの）
     * @param primaryKeyColumn 主キーのカラム名
     * @param rawValue         文字列として受け取った主キー値
     * @return 数値型カラムなら {@link Long}、それ以外は文字列のまま
     * @throws IllegalStateException メタ情報の取得に失敗した場合
     */
    private Object convertPrimaryKeyValue(String tableName, String primaryKeyColumn, String rawValue) {
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData()
                     .getColumns(null, TARGET_SCHEMA, tableName, primaryKeyColumn)) {
            if (resultSet.next()) {
                int sqlType = resultSet.getInt("DATA_TYPE");
                if (sqlType == Types.BIGINT || sqlType == Types.INTEGER || sqlType == Types.SMALLINT) {
                    return Long.valueOf(rawValue);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("主キーの型判定に失敗しました: " + tableName, e);
        }
        return rawValue;
    }
}
