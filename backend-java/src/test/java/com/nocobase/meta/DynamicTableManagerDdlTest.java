package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PHASE105: DynamicTableManager DDL / 其余方法测试（反射注入 mock JdbcTemplate）。
 *
 * <p>覆盖 Week 7 字段变更（add/drop/rename/alter physical column + mapColumnType/
 * usingClause 全分支）、lock_timeout、getColumns、getNextAutonumber 序列与回退、
 * 以及 validateIdentifier 白名单、bare/physicalTableName。
 */
class DynamicTableManagerDdlTest {

    private DynamicTableManager manager;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        manager = new DynamicTableManager(mock(DataSource.class));
        jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(manager, "jdbc", jdbc);
    }

    private String capturedExecuteSql() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(cap.capture());
        return cap.getValue();
    }

    // ── DDL：add/drop/rename ──────────────────────────────
    @Test
    void addPhysicalColumn_executesAlter() {
        manager.addPhysicalColumn("c1", "amount", "NUMERIC");

        assertThat(capturedExecuteSql()).contains("ADD COLUMN amount NUMERIC");
    }

    @Test
    void addPhysicalColumn_invalidIdentifier_throws() {
        assertThatThrownBy(() -> manager.addPhysicalColumn("c1", "bad name", "TEXT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法标识符");
    }

    @Test
    void dropPhysicalColumn_executesAlter() {
        manager.dropPhysicalColumn("c1", "amount");

        assertThat(capturedExecuteSql()).contains("DROP COLUMN amount");
    }

    @Test
    void renamePhysicalColumn_executesAlter() {
        manager.renamePhysicalColumn("c1", "old_col", "new_col");

        assertThat(capturedExecuteSql()).contains("RENAME COLUMN old_col TO new_col");
    }

    @Test
    void renamePhysicalColumn_invalidName_throws() {
        assertThatThrownBy(() -> manager.renamePhysicalColumn("c1", "ok", "bad!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── DDL：alter + mapColumnType/usingClause 全分支 ─────
    @Test
    void alterPhysicalColumn_text_noUsingClause() {
        manager.alterPhysicalColumn("c1", "f", "TEXT");

        String sql = capturedExecuteSql();
        assertThat(sql).contains("ALTER COLUMN f TYPE TEXT");
        assertThat(sql).doesNotContain("USING");
    }

    @Test
    void alterPhysicalColumn_numeric_usesZeroGuard() {
        manager.alterPhysicalColumn("c1", "f", "NUMERIC");

        assertThat(capturedExecuteSql()).contains("TYPE NUMERIC USING (0)");
    }

    @Test
    void alterPhysicalColumn_boolean_usesBoolGuard() {
        manager.alterPhysicalColumn("c1", "f", "BOOLEAN");

        assertThat(capturedExecuteSql()).contains("USING (TRUE OR FALSE)");
    }

    @Test
    void alterPhysicalColumn_timestamp_usesNowGuard() {
        manager.alterPhysicalColumn("c1", "f", "TIMESTAMPTZ");

        assertThat(capturedExecuteSql()).contains("USING (NOW())");
    }

    @Test
    void alterPhysicalColumn_uuid_usesZeroGuidGuard() {
        manager.alterPhysicalColumn("c1", "f", "UUID");

        assertThat(capturedExecuteSql()).contains("USING ('00000000");
    }

    @Test
    void alterPhysicalColumn_unsupportedType_throws() {
        assertThatThrownBy(() -> manager.alterPhysicalColumn("c1", "f", "INTERVAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的列类型");
    }

    // ── lock_timeout ───────────────────────────────────────
    @Test
    void setAndResetLockTimeout() {
        manager.setLockTimeout(5000);
        verify(jdbc).execute("SET lock_timeout = '5000ms'");

        manager.resetLockTimeout();
        verify(jdbc).execute("SET lock_timeout = '0'");
    }

    // ── getColumns ─────────────────────────────────────────
    @Test
    void getColumns_queriesInformationSchema() {
        when(jdbc.queryForList(anyString(), eq("data_c1"), eq("public")))
                .thenReturn(List.of(Map.of("column_name", "id", "data_type", "uuid")));

        List<Map<String, Object>> cols = manager.getColumns("c1");

        assertThat(cols).hasSize(1);
        assertThat(cols.get(0).get("column_name")).isEqualTo("id");
    }

    @Test
    void getColumns_nullSchema_fallsBackToPublic() {
        // TenantContext 未设置时 currentSchema() 默认 public
        when(jdbc.queryForList(anyString(), eq("data_c1"), eq("public"))).thenReturn(List.of());

        assertThat(manager.getColumns("c1")).isEmpty();
    }

    // ── 表名辅助 ───────────────────────────────────────────
    @Test
    void bareTableName_prefixesData() {
        assertThat(DynamicTableManager.bareTableName("orders")).isEqualTo("data_orders");
    }

    @Test
    void physicalTableName_qualifiesWithCurrentSchema() {
        String full = DynamicTableManager.physicalTableName("orders");
        assertThat(full).endsWith(".data_orders");
    }

    // ── 基础 DML ───────────────────────────────────────────
    @Test
    void createTable_executesDdl() {
        manager.createTable("c1");
        // 两次 execute：建表 + 建 GIN 索引
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).execute(cap.capture());
        assertThat(cap.getAllValues().get(0)).contains("CREATE TABLE IF NOT EXISTS").contains("data_c1");
        assertThat(cap.getAllValues().get(1)).contains("CREATE INDEX");
    }

    @Test
    void dropTable_executesDdl() {
        manager.dropTable("c1");

        assertThat(capturedExecuteSql()).contains("DROP TABLE");
    }

    @Test
    void tableExists_true() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("data_c1"), eq("public")))
                .thenReturn(1);

        assertThat(manager.tableExists("c1")).isTrue();
    }

    @Test
    void tableExists_false_onNullCount() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("data_c1"), eq("public")))
                .thenReturn(null);

        assertThat(manager.tableExists("c1")).isFalse();
    }

    @Test
    void insertRecord_executes() {
        manager.insertRecord("c1", "id-1", "{}");
        verify(jdbc).update(anyString(), eq("id-1"), eq("{}"));
    }

    @Test
    void getRecord_present_returnsJson() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("id-1")))
                .thenReturn(List.of("{\"id\":\"id-1\"}"));

        assertThat(manager.getRecord("c1", "id-1")).isPresent();
    }

    @Test
    void getRecord_absent_returnsEmpty() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("id-1"))).thenReturn(List.of());

        assertThat(manager.getRecord("c1", "id-1")).isEmpty();
    }

    @Test
    void updateRecord_returnsAffectedRows() {
        when(jdbc.update(anyString(), eq("{}"), eq("id-1"))).thenReturn(1);

        assertThat(manager.updateRecord("c1", "id-1", "{}")).isEqualTo(1);
    }

    @Test
    void deleteRecord_returnsAffectedRows() {
        when(jdbc.update(anyString(), eq("id-1"))).thenReturn(1);

        assertThat(manager.deleteRecord("c1", "id-1")).isEqualTo(1);
    }

    // ── getNextAutonumber：序列 vs 回退 ────────────────────
    @Test
    void getNextAutonumber_sequencePath() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);

        assertThat(manager.getNextAutonumber("c1", "seq")).isEqualTo(42L);
        verify(jdbc).execute("CREATE SEQUENCE IF NOT EXISTS autonumber_c1_seq");
    }

    @Test
    void getNextAutonumber_fallbackWhenSequenceFails() {
        // 序列创建失败 → 回退 max 查询；max=null → 0+1=1
        doThrow(new RuntimeException("no sequence support"))
                .when(jdbc).execute(anyString());
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(null);

        assertThat(manager.getNextAutonumber("c1", "seq")).isEqualTo(1L);
    }

    @Test
    void getNextAutonumber_fallbackWithMax() {
        doThrow(new RuntimeException("no sequence support"))
                .when(jdbc).execute(anyString());
        // 第一次 queryForObject（回退 max）返回 9
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(9L);

        assertThat(manager.getNextAutonumber("c1", "seq")).isEqualTo(10L);
    }
}
