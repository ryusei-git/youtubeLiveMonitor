package com.example.monitor.service;

import com.example.monitor.dto.TableDataResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import javax.sql.DataSource;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DataSource#getConnection()} は呼ぶたびに新しい {@link Connection} を返す設計
 * （try-with-resources で毎回クローズしているため）なので、テストでは常に同じモックの
 * {@link Connection} を返すよう {@code lenient} 的にスタブしている。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseTableService")
class DatabaseTableServiceTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Connection connection;

    @Mock
    private DatabaseMetaData databaseMetaData;

    @Mock
    private AuditLogger auditLogger;

    private DatabaseTableService databaseTableService;

    @BeforeEach
    void setUp() throws SQLException {
        databaseTableService = new DatabaseTableService(dataSource, jdbcTemplate, auditLogger);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
    }

    /** 1件だけ値を返し、2回目以降のnext()はfalseになるResultSetのモックを作る。 */
    private ResultSet singleRowResultSet(String columnName, String value) throws SQLException {
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(columnName)).thenReturn(value);
        return resultSet;
    }

    /** {@code columnLabel} の列に {@code values} を順に返し、その後 {@code next()} が false になる ResultSet を作る。 */
    private static ResultSet stringRows(String columnLabel, String... values) throws SQLException {
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        if (values.length == 0) {
            when(resultSet.next()).thenReturn(false);
            return resultSet;
        }
        Boolean[] rest = new Boolean[values.length];
        java.util.Arrays.fill(rest, true);
        rest[values.length - 1] = false;
        when(resultSet.next()).thenReturn(true, rest);
        when(resultSet.getString(columnLabel)).thenReturn(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
        return resultSet;
    }

    /** メタ情報のテーブル一覧が、この名前をこの順に返すようにする。 */
    private void stubTables(String... tableNames) throws SQLException {
        ResultSet resultSet = stringRows("TABLE_NAME", tableNames);
        when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(resultSet);
    }

    /** メタ情報の列一覧が、この列名をこの順に返すようにする。 */
    private void stubColumns(String table, String... columns) throws SQLException {
        ResultSet resultSet = stringRows("COLUMN_NAME", columns);
        when(databaseMetaData.getColumns(eq(null), eq("PUBLIC"), eq(table), eq("%"))).thenReturn(resultSet);
    }

    /** 主キーの列名を返すようにする。 */
    private void stubPrimaryKey(String table, String column) throws SQLException {
        ResultSet resultSet = singleRowResultSet("COLUMN_NAME", column);
        when(databaseMetaData.getPrimaryKeys(eq(null), eq("PUBLIC"), eq(table))).thenReturn(resultSet);
    }

    /** 主キーの列の型（{@link Types} の値）を返すようにする。 */
    private void stubPrimaryKeyType(String table, String column, int sqlType) throws SQLException {
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getInt("DATA_TYPE")).thenReturn(sqlType);
        when(databaseMetaData.getColumns(eq(null), eq("PUBLIC"), eq(table), eq(column))).thenReturn(resultSet);
    }

    /** バイナリの列（{@code findBinaryColumns} の結果）を返すようにする。 */
    private void stubBinaryColumns(String table, List<String> columns) {
        when(jdbcTemplate.query(eq("SELECT * FROM " + table + " WHERE 1 = 0"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<List<String>>>any()))
                .thenReturn(columns);
    }

    @Nested
    @DisplayName("listTableNames()")
    class ListTableNames {

        @Test
        @DisplayName("正常系：DBメタ情報から取得したテーブル名一覧を返す")
        void testMethod01() throws SQLException {
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true, true, false);
            when(resultSet.getString("TABLE_NAME")).thenReturn("CHANNELS", "NOTIFICATION_HISTORY");
            when(databaseMetaData.getTables(eq(null), eq("PUBLIC"), eq("%"), any())).thenReturn(resultSet);

            List<String> result = databaseTableService.listTableNames();

            assertThat(result).containsExactly("CHANNELS", "NOTIFICATION_HISTORY");
        }

        @Test
        @DisplayName("正常系：テーブルが1つも無い場合は空リストを返す")
        void testMethod02() throws SQLException {
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.next()).thenReturn(false);
            when(databaseMetaData.getTables(eq(null), eq("PUBLIC"), eq("%"), any())).thenReturn(resultSet);

            List<String> result = databaseTableService.listTableNames();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("異常系：メタ情報取得でSQLExceptionが発生するとIllegalStateExceptionに変換される")
        void testMethod03() throws SQLException {
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenThrow(new SQLException("接続エラー"));

            assertThatThrownBy(() -> databaseTableService.listTableNames())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("正常系：監査ログ（AUDIT_LOGS）は一覧から除外される")
        void testMethod04() throws SQLException {
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true, true, false);
            when(resultSet.getString("TABLE_NAME")).thenReturn("CHANNELS", "AUDIT_LOGS");
            when(databaseMetaData.getTables(eq(null), eq("PUBLIC"), eq("%"), any())).thenReturn(resultSet);

            List<String> result = databaseTableService.listTableNames();

            assertThat(result).containsExactly("CHANNELS");
        }

        @Test
        @DisplayName("正常系：パスワードハッシュ等を持つ APP_USERS と招待の token を持つ INVITATIONS も一覧から除外される")
        void testMethod05() throws SQLException {
            stubTables("CHANNELS", "APP_USERS", "INVITATIONS", "RECORDINGS");

            List<String> result = databaseTableService.listTableNames();

            assertThat(result).containsExactly("CHANNELS", "RECORDINGS");
        }
    }

    @Nested
    @DisplayName("listTableSummaries()")
    class ListTableSummaries {

        @Test
        @DisplayName("正常系：対応表にあるテーブルは論理名付きで返る")
        void testMethod01() throws SQLException {
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true, true, false);
            when(resultSet.getString("TABLE_NAME")).thenReturn("CHANNELS", "RECORDINGS");
            when(databaseMetaData.getTables(eq(null), eq("PUBLIC"), eq("%"), any())).thenReturn(resultSet);

            List<com.example.monitor.dto.TableSummary> result = databaseTableService.listTableSummaries();

            assertThat(result).containsExactly(
                    new com.example.monitor.dto.TableSummary("CHANNELS", "チャンネル"),
                    new com.example.monitor.dto.TableSummary("RECORDINGS", "録画履歴"));
        }

        @Test
        @DisplayName("正常系：対応表に無いテーブルは物理名がそのまま論理名になる")
        void testMethod02() throws SQLException {
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getString("TABLE_NAME")).thenReturn("UNKNOWN_TABLE");
            when(databaseMetaData.getTables(eq(null), eq("PUBLIC"), eq("%"), any())).thenReturn(resultSet);

            List<com.example.monitor.dto.TableSummary> result = databaseTableService.listTableSummaries();

            assertThat(result).containsExactly(new com.example.monitor.dto.TableSummary("UNKNOWN_TABLE", "UNKNOWN_TABLE"));
        }

        @Test
        @DisplayName("正常系：監査ログ（AUDIT_LOGS）は論理名付き一覧にも出てこない")
        void testMethod03() throws SQLException {
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true, true, false);
            when(resultSet.getString("TABLE_NAME")).thenReturn("CHANNELS", "AUDIT_LOGS");
            when(databaseMetaData.getTables(eq(null), eq("PUBLIC"), eq("%"), any())).thenReturn(resultSet);

            List<com.example.monitor.dto.TableSummary> result = databaseTableService.listTableSummaries();

            assertThat(result).containsExactly(new com.example.monitor.dto.TableSummary("CHANNELS", "チャンネル"));
        }

        @Test
        @DisplayName("正常系：ログイン利用者（APP_USERS）・招待（INVITATIONS）は論理名付き一覧にも出てこない")
        void testMethod04() throws SQLException {
            stubTables("CHANNELS", "APP_USERS", "INVITATIONS");

            List<com.example.monitor.dto.TableSummary> result = databaseTableService.listTableSummaries();

            assertThat(result).containsExactly(new com.example.monitor.dto.TableSummary("CHANNELS", "チャンネル"));
        }
    }

    @Nested
    @DisplayName("getTableData()")
    class GetTableData {

        @Test
        @DisplayName("正常系：存在するテーブルの内容をページ単位で返す")
        void testMethod01() throws SQLException {
            ResultSet tablesResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(tablesResultSet.next()).thenReturn(true, false);
            when(tablesResultSet.getString("TABLE_NAME")).thenReturn("CHANNELS");
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

            ResultSet columnsResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(columnsResultSet.next()).thenReturn(true, true, false);
            when(columnsResultSet.getString("COLUMN_NAME")).thenReturn("ID", "CHANNEL_NAME");
            when(databaseMetaData.getColumns(eq(null), eq("PUBLIC"), eq("CHANNELS"), eq("%")))
                    .thenReturn(columnsResultSet);

            ResultSet pkResultSet = singleRowResultSet("COLUMN_NAME", "ID");
            when(databaseMetaData.getPrimaryKeys(eq(null), eq("PUBLIC"), eq("CHANNELS"))).thenReturn(pkResultSet);

            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM CHANNELS"), eq(Long.class))).thenReturn(2L);
            when(jdbcTemplate.query(eq("SELECT * FROM CHANNELS LIMIT ? OFFSET ?"),
                    any(ColumnMapRowMapper.class), eq(50), eq(0)))
                    .thenReturn(List.of(Map.of("ID", 1, "CHANNEL_NAME", "テスト")));

            TableDataResponse response = databaseTableService.getTableData("channels", 0, 50);

            assertThat(response.tableName()).isEqualTo("CHANNELS");
            assertThat(response.tableLabel()).isEqualTo("チャンネル");
            assertThat(response.columns()).containsExactly("ID", "CHANNEL_NAME");
            assertThat(response.columnLabels())
                    .containsEntry("ID", "ID")
                    .containsEntry("CHANNEL_NAME", "チャンネル名");
            assertThat(response.primaryKeyColumn()).isEqualTo("ID");
            assertThat(response.totalElements()).isEqualTo(2L);
            assertThat(response.rows()).hasSize(1);
        }

        @Test
        @DisplayName("正常系：対応表に無いテーブル・カラムは物理名がそのまま論理名になる")
        void testMethod03() throws SQLException {
            ResultSet tablesResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(tablesResultSet.next()).thenReturn(true, false);
            when(tablesResultSet.getString("TABLE_NAME")).thenReturn("UNKNOWN_TABLE");
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

            ResultSet columnsResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(columnsResultSet.next()).thenReturn(true, false);
            when(columnsResultSet.getString("COLUMN_NAME")).thenReturn("MYSTERY_COLUMN");
            when(databaseMetaData.getColumns(eq(null), eq("PUBLIC"), eq("UNKNOWN_TABLE"), eq("%")))
                    .thenReturn(columnsResultSet);

            ResultSet pkResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(pkResultSet.next()).thenReturn(false);
            when(databaseMetaData.getPrimaryKeys(eq(null), eq("PUBLIC"), eq("UNKNOWN_TABLE"))).thenReturn(pkResultSet);

            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM UNKNOWN_TABLE"), eq(Long.class))).thenReturn(0L);
            when(jdbcTemplate.query(eq("SELECT * FROM UNKNOWN_TABLE LIMIT ? OFFSET ?"),
                    any(ColumnMapRowMapper.class), eq(50), eq(0)))
                    .thenReturn(List.of());

            TableDataResponse response = databaseTableService.getTableData("UNKNOWN_TABLE", 0, 50);

            assertThat(response.tableLabel()).isEqualTo("UNKNOWN_TABLE");
            assertThat(response.columnLabels()).containsEntry("MYSTERY_COLUMN", "MYSTERY_COLUMN");
        }

        @Test
        @DisplayName("異常系：存在しないテーブル名を指定するとIllegalArgumentExceptionが発生する")
        void testMethod02() throws SQLException {
            ResultSet tablesResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(tablesResultSet.next()).thenReturn(false);
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

            assertThatThrownBy(() -> databaseTableService.getTableData("UNKNOWN", 0, 50))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系：監査ログ（AUDIT_LOGS）を指定すると「存在しないテーブル」としてIllegalArgumentExceptionが発生する")
        void testMethod04() throws SQLException {
            ResultSet tablesResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(tablesResultSet.next()).thenReturn(true, false);
            when(tablesResultSet.getString("TABLE_NAME")).thenReturn("AUDIT_LOGS");
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

            assertThatThrownBy(() -> databaseTableService.getTableData("AUDIT_LOGS", 0, 50))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系：APP_USERS を指定すると「存在しないテーブル」として拒否され、SQL を発行しない")
        void testMethod05() throws SQLException {
            stubTables("CHANNELS", "APP_USERS");

            assertThatThrownBy(() -> databaseTableService.getTableData("APP_USERS", 0, 50))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("存在しないテーブルです");
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("異常系：小文字で app_users と指定しても拒否される")
        void testMethod06() throws SQLException {
            stubTables("CHANNELS", "APP_USERS");

            assertThatThrownBy(() -> databaseTableService.getTableData("app_users", 0, 50))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("存在しないテーブルです");
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("異常系：INVITATIONS を指定すると「存在しないテーブル」として拒否され、SQL を発行しない")
        void testMethod07() throws SQLException {
            stubTables("CHANNELS", "INVITATIONS");

            assertThatThrownBy(() -> databaseTableService.getTableData("INVITATIONS", 0, 50))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("存在しないテーブルです");
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("正常系：バイナリの列は中身を読まず「（バイナリ n バイト）」の文字で返り、列名が binaryColumns に入る")
        void testMethod08() throws SQLException {
            stubTables("VIDEO_THUMBNAILS");
            stubColumns("VIDEO_THUMBNAILS", "ID", "CONTENT", "RAW");
            stubPrimaryKey("VIDEO_THUMBNAILS", "ID");
            stubBinaryColumns("VIDEO_THUMBNAILS", List.of("CONTENT", "RAW"));
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM VIDEO_THUMBNAILS"), eq(Long.class))).thenReturn(1L);
            when(jdbcTemplate.query(eq("SELECT * FROM VIDEO_THUMBNAILS LIMIT ? OFFSET ?"),
                    any(ColumnMapRowMapper.class), eq(50), eq(0)))
                    .thenReturn(List.of());

            TableDataResponse response = databaseTableService.getTableData("VIDEO_THUMBNAILS", 0, 50);

            assertThat(response.binaryColumns()).containsExactly("CONTENT", "RAW");

            // 実際の行の変換を取り出し、バイナリの値を持つ 1 行を通す
            ArgumentCaptor<ColumnMapRowMapper> mapperCaptor = ArgumentCaptor.forClass(ColumnMapRowMapper.class);
            verify(jdbcTemplate).query(eq("SELECT * FROM VIDEO_THUMBNAILS LIMIT ? OFFSET ?"),
                    mapperCaptor.capture(), eq(50), eq(0));

            Blob blob = org.mockito.Mockito.mock(Blob.class);
            when(blob.length()).thenReturn(12_345L);
            ResultSetMetaData metaData = org.mockito.Mockito.mock(ResultSetMetaData.class);
            when(metaData.getColumnCount()).thenReturn(3);
            when(metaData.getColumnLabel(1)).thenReturn("ID");
            when(metaData.getColumnLabel(2)).thenReturn("CONTENT");
            when(metaData.getColumnLabel(3)).thenReturn("RAW");
            ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
            when(row.getMetaData()).thenReturn(metaData);
            when(row.getObject(1)).thenReturn("abc");
            when(row.getObject(2)).thenReturn(blob);
            when(row.getObject(3)).thenReturn(new byte[]{1, 2, 3});

            Map<String, Object> mapped = mapperCaptor.getValue().mapRow(row, 0);

            assertThat(mapped)
                    .containsEntry("ID", "abc")
                    .containsEntry("CONTENT", "（バイナリ 12,345 バイト）")
                    .containsEntry("RAW", "（バイナリ 3 バイト）");
            verify(blob, never()).getBytes(anyLong(), anyInt());
        }
    }

    @Nested
    @DisplayName("updateRow()")
    class UpdateRow {

        @Test
        @DisplayName("正常系：主キー以外の指定カラムを更新する")
        void testMethod01() throws SQLException {
            ResultSet tablesResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(tablesResultSet.next()).thenReturn(true, false);
            when(tablesResultSet.getString("TABLE_NAME")).thenReturn("CHANNELS");
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

            ResultSet pkResultSet = singleRowResultSet("COLUMN_NAME", "ID");
            when(databaseMetaData.getPrimaryKeys(eq(null), eq("PUBLIC"), eq("CHANNELS"))).thenReturn(pkResultSet);

            ResultSet columnsResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(columnsResultSet.next()).thenReturn(true, true, false);
            when(columnsResultSet.getString("COLUMN_NAME")).thenReturn("ID", "CHANNEL_NAME");
            when(databaseMetaData.getColumns(eq(null), eq("PUBLIC"), eq("CHANNELS"), eq("%")))
                    .thenReturn(columnsResultSet);

            ResultSet pkTypeResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(pkTypeResultSet.next()).thenReturn(true, false);
            when(pkTypeResultSet.getInt("DATA_TYPE")).thenReturn(Types.BIGINT);
            when(databaseMetaData.getColumns(eq(null), eq("PUBLIC"), eq("CHANNELS"), eq("ID")))
                    .thenReturn(pkTypeResultSet);

            when(jdbcTemplate.query(eq("SELECT * FROM CHANNELS WHERE 1 = 0"),
                    org.mockito.ArgumentMatchers.<ResultSetExtractor<List<String>>>any()))
                    .thenReturn(List.of());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            databaseTableService.updateRow("CHANNELS", "1", Map.of("CHANNEL_NAME", "新しい名前"));

            org.mockito.Mockito.verify(jdbcTemplate).update(
                    eq("UPDATE CHANNELS SET CHANNEL_NAME = ? WHERE ID = ?"),
                    eq(new Object[]{"新しい名前", 1L}));
        }

        @Test
        @DisplayName("正常系：主キーの変更指示は無視される")
        void testMethod02() throws SQLException {
            ResultSet tablesResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(tablesResultSet.next()).thenReturn(true, false);
            when(tablesResultSet.getString("TABLE_NAME")).thenReturn("CHANNELS");
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

            ResultSet pkResultSet = singleRowResultSet("COLUMN_NAME", "ID");
            when(databaseMetaData.getPrimaryKeys(eq(null), eq("PUBLIC"), eq("CHANNELS"))).thenReturn(pkResultSet);

            ResultSet columnsResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(columnsResultSet.next()).thenReturn(true, true, false);
            when(columnsResultSet.getString("COLUMN_NAME")).thenReturn("ID", "CHANNEL_NAME");
            when(databaseMetaData.getColumns(eq(null), eq("PUBLIC"), eq("CHANNELS"), eq("%")))
                    .thenReturn(columnsResultSet);

            assertThatThrownBy(() -> databaseTableService.updateRow("CHANNELS", "1", Map.of("ID", 999)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("更新できる項目がありません");
        }

        @Test
        @DisplayName("異常系：主キーが存在しないテーブルを更新しようとするとIllegalStateExceptionが発生する")
        void testMethod03() throws SQLException {
            ResultSet tablesResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(tablesResultSet.next()).thenReturn(true, false);
            when(tablesResultSet.getString("TABLE_NAME")).thenReturn("CHANNELS");
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

            ResultSet pkResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(pkResultSet.next()).thenReturn(false);
            when(databaseMetaData.getPrimaryKeys(eq(null), eq("PUBLIC"), eq("CHANNELS"))).thenReturn(pkResultSet);

            assertThatThrownBy(() -> databaseTableService.updateRow("CHANNELS", "1", Map.of("CHANNEL_NAME", "x")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("異常系：該当する行が無い場合はIllegalArgumentExceptionが発生する")
        void testMethod04() throws SQLException {
            ResultSet tablesResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(tablesResultSet.next()).thenReturn(true, false);
            when(tablesResultSet.getString("TABLE_NAME")).thenReturn("CHANNELS");
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

            ResultSet pkResultSet = singleRowResultSet("COLUMN_NAME", "ID");
            when(databaseMetaData.getPrimaryKeys(eq(null), eq("PUBLIC"), eq("CHANNELS"))).thenReturn(pkResultSet);

            ResultSet columnsResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(columnsResultSet.next()).thenReturn(true, true, false);
            when(columnsResultSet.getString("COLUMN_NAME")).thenReturn("ID", "CHANNEL_NAME");
            when(databaseMetaData.getColumns(eq(null), eq("PUBLIC"), eq("CHANNELS"), eq("%")))
                    .thenReturn(columnsResultSet);

            ResultSet pkTypeResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(pkTypeResultSet.next()).thenReturn(true, false);
            when(pkTypeResultSet.getInt("DATA_TYPE")).thenReturn(Types.BIGINT);
            when(databaseMetaData.getColumns(eq(null), eq("PUBLIC"), eq("CHANNELS"), eq("ID")))
                    .thenReturn(pkTypeResultSet);

            when(jdbcTemplate.query(eq("SELECT * FROM CHANNELS WHERE 1 = 0"),
                    org.mockito.ArgumentMatchers.<ResultSetExtractor<List<String>>>any()))
                    .thenReturn(List.of());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

            assertThatThrownBy(() -> databaseTableService.updateRow("CHANNELS", "999", Map.of("CHANNEL_NAME", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("該当する行が見つかりません");
        }

        @Test
        @DisplayName("異常系：監査ログ（AUDIT_LOGS）は更新しようとしても「存在しないテーブル」として拒否される")
        void testMethod05() throws SQLException {
            ResultSet tablesResultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(tablesResultSet.next()).thenReturn(true, false);
            when(tablesResultSet.getString("TABLE_NAME")).thenReturn("AUDIT_LOGS");
            when(databaseMetaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

            assertThatThrownBy(() -> databaseTableService.updateRow("AUDIT_LOGS", "1", Map.of("DETAIL", "改ざん")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系：APP_USERS の更新（パスワードハッシュ）は「存在しないテーブル」として拒否され、SQL を発行しない")
        void testMethod06() throws SQLException {
            stubTables("CHANNELS", "APP_USERS");

            assertThatThrownBy(() -> databaseTableService.updateRow("APP_USERS", "1", Map.of("PASSWORD_HASH", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("存在しないテーブルです");
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("異常系：INVITATIONS の更新（招待の token）は「存在しないテーブル」として拒否され、SQL を発行しない")
        void testMethod07() throws SQLException {
            stubTables("CHANNELS", "INVITATIONS");

            assertThatThrownBy(() -> databaseTableService.updateRow("INVITATIONS", "1", Map.of("TOKEN", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("存在しないテーブルです");
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("異常系：バイナリの列の更新は断り、UPDATE を発行しない")
        void testMethod08() throws SQLException {
            stubTables("VIDEO_THUMBNAILS");
            stubPrimaryKey("VIDEO_THUMBNAILS", "ID");
            stubColumns("VIDEO_THUMBNAILS", "ID", "CONTENT_TYPE", "CONTENT");
            stubBinaryColumns("VIDEO_THUMBNAILS", List.of("CONTENT"));

            // 主キーの型は調べる前に断るので、stubPrimaryKeyType は呼ばない
            assertThatThrownBy(() -> databaseTableService.updateRow("VIDEO_THUMBNAILS", "abc", Map.of("CONTENT", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("バイナリの列は編集できません: CONTENT");
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("異常系：バイナリの列を小文字で指定しても断る")
        void testMethod09() throws SQLException {
            stubTables("VIDEO_THUMBNAILS");
            stubPrimaryKey("VIDEO_THUMBNAILS", "ID");
            stubColumns("VIDEO_THUMBNAILS", "ID", "CONTENT_TYPE", "CONTENT");
            stubBinaryColumns("VIDEO_THUMBNAILS", List.of("CONTENT"));

            assertThatThrownBy(() -> databaseTableService.updateRow("VIDEO_THUMBNAILS", "abc", Map.of("content", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CONTENT");
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("異常系：DB が値を受け付けないと、DB の文面を返さずに列名入りの IllegalArgumentException にする")
        void testMethod10() throws SQLException {
            stubTables("CHANNELS");
            stubPrimaryKey("CHANNELS", "ID");
            stubColumns("CHANNELS", "ID", "CHANNEL_NAME", "CREATED_AT");
            stubBinaryColumns("CHANNELS", List.of());
            stubPrimaryKeyType("CHANNELS", "ID", Types.BIGINT);
            when(jdbcTemplate.update(anyString(), any(Object[].class)))
                    .thenThrow(new DataIntegrityViolationException("Data conversion error converting \"abc\""));

            assertThatThrownBy(() -> databaseTableService.updateRow("CHANNELS", "1", Map.of("CREATED_AT", "abc")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CREATED_AT")
                    .hasMessageNotContaining("Data conversion error");
        }

        @Test
        @DisplayName("正常系：主キーが文字列の列なら、主キーの値を数値にせず文字列のまま渡す")
        void testMethod11() throws SQLException {
            stubTables("VIDEO_THUMBNAILS");
            stubPrimaryKey("VIDEO_THUMBNAILS", "ID");
            stubColumns("VIDEO_THUMBNAILS", "ID", "CONTENT_TYPE", "CONTENT");
            stubBinaryColumns("VIDEO_THUMBNAILS", List.of("CONTENT"));
            stubPrimaryKeyType("VIDEO_THUMBNAILS", "ID", Types.VARCHAR);
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            databaseTableService.updateRow("VIDEO_THUMBNAILS", "abc", Map.of("CONTENT_TYPE", "image/png"));

            verify(jdbcTemplate).update(
                    eq("UPDATE VIDEO_THUMBNAILS SET CONTENT_TYPE = ? WHERE ID = ?"),
                    eq(new Object[]{"image/png", "abc"}));
        }
    }
}
