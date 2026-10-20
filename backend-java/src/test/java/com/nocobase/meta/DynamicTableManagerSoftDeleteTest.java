package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PHASE125 软删除测试 —— 反射注入 mock JdbcTemplate,断言生成的 SQL 结构。
 *
 * <p>与 {@link DynamicTableManagerAggregateTest} 同策略:动态表 SQL 是 PostgreSQL 方言,
 * 在 H2 上执行会降级/失败,故此处<strong>不真实执行 SQL</strong>,而是捕获并断言 SQL 文本,
 * 以此锁定"软删除而非物理删除"这一核心语义。
 */
class DynamicTableManagerSoftDeleteTest {

    private static final String ID = "11111111-1111-1111-1111-111111111111";

    private DynamicTableManager manager;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        manager = new DynamicTableManager(mock(DataSource.class));
        jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(manager, "jdbc", jdbc);
        // update(String, Object...)
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        // queryForList(String, Class, Object...) —— listRecords/getRecord/listDeleted 走此重载
        when(jdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(List.of("{}"));
    }

    private String captureUpdateSql() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(cap.capture(), any(Object[].class));
        return cap.getValue();
    }

    private String captureQuerySql() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(cap.capture(), any(Class.class), any(Object[].class));
        return cap.getValue();
    }

    @Test
    void deleteRecord_isSoftDelete_notPhysicalDelete() {
        manager.deleteRecord("c1", ID);

        String sql = captureUpdateSql();
        assertThat(sql).contains("UPDATE").contains("deleted_at = NOW()");
        assertThat(sql).doesNotContain("DELETE FROM");
    }

    @Test
    void deleteRecord_withUser_alsoRecordsDeletedBy() {
        manager.deleteRecord("c1", ID, UUID.randomUUID());

        String sql = captureUpdateSql();
        assertThat(sql).contains("deleted_at = NOW()").contains("deleted_by");
    }

    @Test
    void deleteRecord_idempotent_skipsAlreadyDeleted() {
        manager.deleteRecord("c1", ID);

        // AND deleted_at IS NULL:已删记录不重复软删(幂等)
        assertThat(captureUpdateSql()).contains("AND deleted_at IS NULL");
    }

    @Test
    void hardDeleteRecord_isPhysicalDelete() {
        manager.hardDeleteRecord("c1", ID);

        assertThat(captureUpdateSql()).contains("DELETE FROM");
    }

    @Test
    void restoreRecord_clearsDeletedAt() {
        manager.restoreRecord("c1", ID);

        String sql = captureUpdateSql();
        assertThat(sql).contains("UPDATE").contains("deleted_at = NULL");
        assertThat(sql).contains("AND deleted_at IS NOT NULL");
    }

    @Test
    void listRecords_excludesSoftDeleted() {
        manager.listRecords("c1", 10);

        assertThat(captureQuerySql()).contains("WHERE deleted_at IS NULL");
    }

    @Test
    void getRecord_excludesSoftDeleted() {
        manager.getRecord("c1", ID);

        assertThat(captureQuerySql()).contains("deleted_at IS NULL");
    }

    @Test
    void getRecordIncludeDeleted_doesNotFilter() {
        manager.getRecordIncludeDeleted("c1", ID);

        assertThat(captureQuerySql()).doesNotContain("deleted_at IS NULL");
    }

    @Test
    void listDeleted_onlySoftDeleted_orderedByDeleteTime() {
        manager.listDeleted("c1", 10);

        String sql = captureQuerySql();
        assertThat(sql).contains("deleted_at IS NOT NULL").contains("ORDER BY deleted_at DESC");
    }

    @Test
    void ensureDeletedAtColumn_isIdempotentAlter() {
        manager.ensureDeletedAtColumn("c1");

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).execute(cap.capture());
        assertThat(cap.getAllValues())
                .anySatisfy(sql -> assertThat(sql)
                        .contains("ALTER TABLE")
                        .contains("ADD COLUMN IF NOT EXISTS deleted_at"));
    }

    @Test
    void ensureDeletedAtColumn_rejectsUnsafeIdentifier() {
        // PHASE116 加固:非法标识符必须抛错,绝不拼进 DDL
        assertThatThrownBy(() -> manager.ensureDeletedAtColumn("bad;name"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
