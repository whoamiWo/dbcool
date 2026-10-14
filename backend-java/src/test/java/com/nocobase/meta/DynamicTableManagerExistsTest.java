package com.nocobase.meta;

import static org.junit.jupiter.api.Assertions.*;

import javax.sql.DataSource;

import com.nocobase.meta.DynamicTableManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PHASE105: DynamicTableManager.existsByFieldValue 覆盖（唯一值校验 + SQL 注入防护）。
 *
 * <p>目标：覆盖非法字段名、排除 ID 分支、空结果/有结果。
 */
public class DynamicTableManagerExistsTest {

    private DynamicTableManager manager;
    private JdbcTemplate template;

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = Mockito.mock(DataSource.class);
        manager = new DynamicTableManager(ds);
        // 替换内部 jdbc
        java.lang.reflect.Field f = DynamicTableManager.class.getDeclaredField("jdbc");
        f.setAccessible(true);
        template = Mockito.mock(JdbcTemplate.class);
        f.set(manager, template);
    }

    @Test
    void illegalFieldName_returnsFalseWithoutQuery() {
        // 非法字段名直接返回 false，不应触发任何查询
        assertFalse(manager.existsByFieldValue("orders", "123bad", "x", null));
        Mockito.verify(template, Mockito.never()).queryForObject(Mockito.anyString(),
                Mockito.eq(Integer.class), Mockito.any());
    }

    @Test
    void validField_noMatch_returnsFalse() {
        Mockito.when(template.queryForObject(Mockito.anyString(), Mockito.eq(Integer.class), Mockito.any()))
                .thenReturn(0);
        assertFalse(manager.existsByFieldValue("orders", "amount", "999", null));
    }

    @Test
    void validField_match_returnsTrue() {
        Mockito.when(template.queryForObject(Mockito.anyString(), Mockito.eq(Integer.class), Mockito.any()))
                .thenReturn(1);
        assertTrue(manager.existsByFieldValue("orders", "amount", "100", null));
    }

    @Test
    void withExcludeId_generatesCorrectSql() {
        Mockito.when(template.queryForObject(Mockito.anyString(), Mockito.eq(Integer.class), Mockito.any()))
                .thenReturn(0);
        manager.existsByFieldValue("orders", "amount", "100", "uuid-1");
        // 应传递 2 个参数 (value + excludeId)，varargs 拆成独立参数匹配
        Mockito.verify(template, Mockito.times(1)).queryForObject(
                Mockito.contains("id <> ?::uuid"),
                Mockito.eq(Integer.class),
                Mockito.eq("100"),
                Mockito.eq("uuid-1"));
    }
}
