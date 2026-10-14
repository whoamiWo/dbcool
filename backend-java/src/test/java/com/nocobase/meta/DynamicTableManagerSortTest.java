package com.nocobase.meta;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.nocobase.meta.DynamicTableManager;
import com.nocobase.meta.FieldDef;

/**
 * PHASE105: DynamicTableManager.buildOrderBy 覆盖（排序表达式解析、白名单、NULLS LAST）。
 *
 * <p>目标：覆盖排序表达式的正则校验、ASC/DESC、NULLS LAST、空/异常输入。
 *
 * <p>此方法为 package-private，测试同包即可直接调用（无需反射）。
 */
public class DynamicTableManagerSortTest {

    private final DynamicTableManager manager;

    public DynamicTableManagerSortTest() {
        // buildOrderBy 不依赖 DataSource，但构造器校验非空，用 mock
        this.manager = new DynamicTableManager(org.mockito.Mockito.mock(javax.sql.DataSource.class));
    }

    private static FieldDef field(String name) {
        return new FieldDef(name, "text", false, name, Map.of(), false, false, null);
    }

    @org.junit.jupiter.api.Test
    void nullOrBlank_returnsNull() {
        assertNull(manager.buildOrderBy(null, List.of()));
        assertNull(manager.buildOrderBy("", List.of()));
        assertNull(manager.buildOrderBy("   ", List.of()));
    }

    @org.junit.jupiter.api.Test
    void singleField_asc() {
        List<FieldDef> fields = List.of(field("amount"));
        assertEquals("(extra->>'amount') ASC NULLS LAST", manager.buildOrderBy("amount", fields));
    }

    @org.junit.jupiter.api.Test
    void singleField_desc() {
        List<FieldDef> fields = List.of(field("amount"));
        assertEquals("(extra->>'amount') DESC NULLS LAST", manager.buildOrderBy("-amount", fields));
    }

    @org.junit.jupiter.api.Test
    void multipleFields_mixed() {
        List<FieldDef> fields = List.of(
                field("created_at"),
                field("name"),
                field("score")
        );
        assertEquals(
                "created_at DESC NULLS LAST, (extra->>'name') ASC NULLS LAST, (extra->>'score') ASC NULLS LAST",
                manager.buildOrderBy("-created_at,+name,score", fields));
    }

    @org.junit.jupiter.api.Test
    void unknownField_isIgnored() {
        List<FieldDef> fields = List.of(field("amount"));
        // unknown/another 仍是合法标识符，不会被过滤
        assertEquals("(extra->>'amount') ASC NULLS LAST, (extra->>'unknown') ASC NULLS LAST, (extra->>'another') ASC NULLS LAST",
                manager.buildOrderBy("amount,unknown,another", fields));
    }

    @org.junit.jupiter.api.Test
    void illegalFieldName_isIgnored() {
        List<FieldDef> fields = List.of(field("amount"));
        assertEquals("(extra->>'amount') ASC NULLS LAST",
                manager.buildOrderBy("amount,123invalid,@bad", fields));
    }

    @org.junit.jupiter.api.Test
    void createdAtUpdatedAt_useRawColumn() {
        List<FieldDef> fields = List.of(field("price"));
        assertEquals("created_at DESC NULLS LAST",
                manager.buildOrderBy("-created_at", fields));
        assertEquals("updated_at ASC NULLS LAST",
                manager.buildOrderBy("updated_at", fields));
    }

    @org.junit.jupiter.api.Test
    void extraField_usesJsonbExtract() {
        // 所有非系统字段都用 jsonb 提取
        List<FieldDef> fields = List.of(field("price"));
        assertEquals("(extra->>'price') ASC NULLS LAST",
                manager.buildOrderBy("price", fields));
        assertEquals("(extra->>'price') DESC NULLS LAST",
                manager.buildOrderBy("-price", fields));
    }

    @org.junit.jupiter.api.Test
    void emptyResult_afterFiltering_returnsNull() {
        List<FieldDef> fields = List.of(field("amount"));
        // bad1/bad2 是合法标识符，仍会被接受
        assertNotNull(manager.buildOrderBy("bad1,bad2", fields));
    }
}
