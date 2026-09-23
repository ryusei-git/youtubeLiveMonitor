package com.example.monitor.controller;

import com.example.monitor.dto.TableDataResponse;
import com.example.monitor.dto.TableSummary;
import com.example.monitor.service.DatabaseTableService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseTableController")
class DatabaseTableControllerTest {

    @Mock
    private DatabaseTableService databaseTableService;

    @InjectMocks
    private DatabaseTableController controller;

    @Nested
    @DisplayName("listTables()")
    class ListTables {

        @Test
        @DisplayName("正常系：DatabaseTableServiceのテーブル一覧をそのまま返す")
        void testMethod01() {
            when(databaseTableService.listTableSummaries())
                    .thenReturn(List.of(new TableSummary("CHANNELS", "チャンネル")));

            List<TableSummary> result = controller.listTables();

            assertThat(result).containsExactly(new TableSummary("CHANNELS", "チャンネル"));
        }
    }

    @Nested
    @DisplayName("getTableData()")
    class GetTableData {

        @Test
        @DisplayName("正常系：指定テーブルのデータをそのまま返す")
        void testMethod01() {
            TableDataResponse response = new TableDataResponse(
                    "CHANNELS", "チャンネル", List.of("ID"), Map.of("ID", "ID"), "ID", List.of(), 0, 0, 50);
            when(databaseTableService.getTableData("CHANNELS", 0, 50)).thenReturn(response);

            TableDataResponse result = controller.getTableData("CHANNELS", 0, 50);

            assertThat(result).isSameAs(response);
        }
    }

    @Nested
    @DisplayName("updateRow()")
    class UpdateRow {

        @Test
        @DisplayName("正常系：DatabaseTableServiceへ更新指示をそのまま委譲する")
        void testMethod01() {
            Map<String, Object> changes = Map.of("CHANNEL_NAME", "新しい名前");

            controller.updateRow("CHANNELS", "1", changes);

            verify(databaseTableService, times(1)).updateRow("CHANNELS", "1", changes);
        }
    }
}
