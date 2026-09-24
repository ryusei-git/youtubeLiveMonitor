package com.example.monitor.service;

import com.example.monitor.dto.TableDataResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private DatabaseTableService databaseTableService;

    @BeforeEach
    void setUp() throws SQLException {
        databaseTableService = new DatabaseTableService(dataSource, jdbcTemplate);
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
    }
}
