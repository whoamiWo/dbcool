package com.nocobase.meta;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1-A: 直接调用生产方法 mapJsonbType，验证 12 种新字段类型的物理列映射。
 * 
 * 每种新类型都有独立测试，确保回退无法通过。
 */
@SpringBootTest
@ActiveProfiles("test")
public class AsyncMigrationServiceMapJsonbTypeTest {

    @Autowired
    private AsyncMigrationService migrationService;

    @Test
    public void testEmailTypeMappedToText() {
        assertEquals("TEXT", migrationService.mapJsonbType("email"));
    }

    @Test
    public void testUrlTypeMappedToText() {
        assertEquals("TEXT", migrationService.mapJsonbType("url"));
    }

    @Test
    public void testPhoneTypeMappedToText() {
        assertEquals("TEXT", migrationService.mapJsonbType("phone"));
    }

    @Test
    public void testCurrencyTypeMappedToNumeric() {
        assertEquals("NUMERIC", migrationService.mapJsonbType("currency"));
    }

    @Test
    public void testPercentTypeMappedToNumeric() {
        assertEquals("NUMERIC", migrationService.mapJsonbType("percent"));
    }

    @Test
    public void testDurationTypeMappedToInteger() {
        assertEquals("INTEGER", migrationService.mapJsonbType("duration"));
    }

    @Test
    public void testRatingTypeMappedToInteger() {
        assertEquals("INTEGER", migrationService.mapJsonbType("rating"));
    }

    @Test
    public void testCreatedTimeTypeMappedToTimestamptz() {
        assertEquals("TIMESTAMPTZ", migrationService.mapJsonbType("createdTime"));
    }

    @Test
    public void testLastModifiedTimeTypeMappedToTimestamptz() {
        assertEquals("TIMESTAMPTZ", migrationService.mapJsonbType("lastModifiedTime"));
    }

    @Test
    public void testCreatedByTypeMappedToUuid() {
        assertEquals("UUID", migrationService.mapJsonbType("createdBy"));
    }

    @Test
    public void testLastModifiedByTypeMappedToUuid() {
        assertEquals("UUID", migrationService.mapJsonbType("lastModifiedBy"));
    }

    @Test
    public void testAutonumberTypeMappedToBigint() {
        assertEquals("BIGINT", migrationService.mapJsonbType("autonumber"));
    }

    @Test
    public void testAllNewTypesMapped() {
        // email / url / phone → TEXT
        assertEquals("TEXT", migrationService.mapJsonbType("email"));
        assertEquals("TEXT", migrationService.mapJsonbType("url"));
        assertEquals("TEXT", migrationService.mapJsonbType("phone"));

        // currency / percent → NUMERIC
        assertEquals("NUMERIC", migrationService.mapJsonbType("currency"));
        assertEquals("NUMERIC", migrationService.mapJsonbType("percent"));

        // duration / rating → INTEGER
        assertEquals("INTEGER", migrationService.mapJsonbType("duration"));
        assertEquals("INTEGER", migrationService.mapJsonbType("rating"));

        // createdTime / lastModifiedTime → TIMESTAMPTZ
        assertEquals("TIMESTAMPTZ", migrationService.mapJsonbType("createdTime"));
        assertEquals("TIMESTAMPTZ", migrationService.mapJsonbType("lastModifiedTime"));

        // createdBy / lastModifiedBy → UUID
        assertEquals("UUID", migrationService.mapJsonbType("createdBy"));
        assertEquals("UUID", migrationService.mapJsonbType("lastModifiedBy"));

        // autonumber → BIGINT
        assertEquals("BIGINT", migrationService.mapJsonbType("autonumber"));
    }

    @Test
    public void testLegacyTypesStillMapped() {
        // 既有类型回归
        assertEquals("TEXT", migrationService.mapJsonbType("text"));
        assertEquals("TEXT", migrationService.mapJsonbType("select"));
        assertEquals("TEXT", migrationService.mapJsonbType("multiSelect"));
        assertEquals("NUMERIC", migrationService.mapJsonbType("number"));
        assertEquals("BOOLEAN", migrationService.mapJsonbType("boolean"));
        assertEquals("TIMESTAMPTZ", migrationService.mapJsonbType("date"));
        assertEquals("TIMESTAMPTZ", migrationService.mapJsonbType("datetime"));
        assertEquals("TEXT", migrationService.mapJsonbType("attachment"));
        assertEquals("UUID", migrationService.mapJsonbType("belongsTo"));
        assertEquals("UUID", migrationService.mapJsonbType("hasMany"));
        assertEquals("TEXT", migrationService.mapJsonbType("formula"));
    }

    @Test
    public void testUnknownTypeRejected() {
        assertThrows(IllegalArgumentException.class, () -> migrationService.mapJsonbType("nonexistent_type"));
    }
}
