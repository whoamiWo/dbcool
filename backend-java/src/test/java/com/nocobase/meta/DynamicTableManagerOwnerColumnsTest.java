package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** R0：归属列写入/读取投影；R1c：聚合 scope 谓词参数顺序。 */
class DynamicTableManagerOwnerColumnsTest {

    private DynamicTableManager manager;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        manager = new DynamicTableManager(mock(DataSource.class));
        jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(manager, "jdbc", jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(List.of("{}"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    }

    @Test
    @DisplayName("insertRecord(actor) 写 created_by")
    void insertRecord_withActor_writesCreatedBy() {
        UUID actor = UUID.randomUUID();
        manager.insertRecord("orders", UUID.randomUUID().toString(), "{}", actor);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("created_by");
        assertThat(args.getValue()).contains(actor.toString());
    }

    @Test
    @DisplayName("updateRecord(actor) 写 updated_by")
    void updateRecord_withActor_writesUpdatedBy() {
        UUID actor = UUID.randomUUID();
        manager.updateRecord("orders", UUID.randomUUID().toString(), "{}", actor);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("updated_by");
        assertThat(args.getValue()).contains(actor.toString());
    }

    @Test
    @DisplayName("列表/详情投影包含系统列，且 DB 列覆盖 extra 同名键")
    void readProjection_includesOwnerColumns() {
        manager.listRecords("orders", 10);
        manager.getRecord("orders", UUID.randomUUID().toString());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeast(2))
                .queryForList(sql.capture(), any(Class.class), any(Object[].class));
        assertThat(sql.getAllValues()).anySatisfy(q -> assertThat(q)
                .contains("'created_by'", "'created_at'", "'updated_by'")
                // extra || jsonb_build_object(...)：右侧覆盖左侧 = DB 列优先
                .contains("COALESCE(extra::jsonb"));
    }

    @Test
    @DisplayName("R3：keyset 分页带 deleted_at IS NULL 且按 id 升序")
    void listRecordsAfter_filtersDeletedAndOrdersById() {
        manager.listRecordsAfter("orders", "abc", 500);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Class.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("deleted_at IS NULL")
                .contains("id > ?::uuid")
                .contains("ORDER BY id ASC");
    }

    @Test
    @DisplayName("R1c：聚合参数顺序 = filters 参数在前、scope 参数在后")
    void aggregate_scopeParamsFollowFilterParams() {
        manager.aggregate("orders",
                List.of("dept"),
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "amount")),
                List.of(new CollectionService.FilterRule("status", "eq", "PAID")),
                "created_by = ?",
                List.of("user-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("WHERE deleted_at IS NULL")
                .contains("AND (created_by = ?)");
        assertThat(args.getValue()).containsExactly("PAID", "user-1");
    }
}
