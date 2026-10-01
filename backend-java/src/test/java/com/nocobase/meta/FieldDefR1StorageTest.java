package com.nocobase.meta;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1-A: 字段类型映射真实测试 — 直接调用生产方法 mapJsonbType。
 */
@SpringBootTest
@ActiveProfiles("test")
public class FieldDefR1StorageTest {

    @Autowired
    private AsyncMigrationService asyncMigrationService;

    @Test
    public void testMapJsonbType_AllTypes() {
        assertEquals("TEXT", asyncMigrationService.mapJsonbType("text"));
        assertEquals("TEXT", asyncMigrationService.mapJsonbType("select"));
        assertEquals("TEXT", asyncMigrationService.mapJsonbType("multiSelect"));
        assertEquals("TEXT", asyncMigrationService.mapJsonbType("email"));
        assertEquals("TEXT", asyncMigrationService.mapJsonbType("url"));
        assertEquals("TEXT", asyncMigrationService.mapJsonbType("phone"));

        assertEquals("NUMERIC", asyncMigrationService.mapJsonbType("number"));
        assertEquals("NUMERIC", asyncMigrationService.mapJsonbType("currency"));
        assertEquals("NUMERIC", asyncMigrationService.mapJsonbType("percent"));

        assertEquals("BOOLEAN", asyncMigrationService.mapJsonbType("boolean"));

        assertEquals("TIMESTAMPTZ", asyncMigrationService.mapJsonbType("date"));
        assertEquals("TIMESTAMPTZ", asyncMigrationService.mapJsonbType("datetime"));
        assertEquals("TIMESTAMPTZ", asyncMigrationService.mapJsonbType("createdTime"));
        assertEquals("TIMESTAMPTZ", asyncMigrationService.mapJsonbType("lastModifiedTime"));

        assertEquals("UUID", asyncMigrationService.mapJsonbType("belongsTo"));
        assertEquals("UUID", asyncMigrationService.mapJsonbType("hasMany"));
        assertEquals("UUID", asyncMigrationService.mapJsonbType("createdBy"));
        assertEquals("UUID", asyncMigrationService.mapJsonbType("lastModifiedBy"));

        assertEquals("INTEGER", asyncMigrationService.mapJsonbType("duration"));
        assertEquals("INTEGER", asyncMigrationService.mapJsonbType("rating"));

        assertEquals("BIGINT", asyncMigrationService.mapJsonbType("autonumber"));

        assertEquals("TEXT", asyncMigrationService.mapJsonbType("formula"));
        assertEquals("TEXT", asyncMigrationService.mapJsonbType("attachment"));
    }

    @Test
    public void testMapJsonbType_UnsupportedThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            asyncMigrationService.mapJsonbType("unsupportedType");
        });
    }
}