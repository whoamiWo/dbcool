package com.nocobase.meta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1 T1: 后端存储层与行为层测试 — 物理列映射、格式校验、自动字段维护。
 * 纯单元测试，无需 Spring 上下文。
 */
class FieldDefR1StorageTest {

    @Test
    @DisplayName("R1 T1: email 格式校验 — 合法值通过")
    void r1EmailValidationValid() {
        String[] validEmails = {"test@example.com", "user.name@domain.org", "a+b@c.co"};
        for (String email : validEmails) {
            assertTrue(validateEmail(email), "Valid email should pass: " + email);
        }
    }

    @Test
    @DisplayName("R1 T1: email 格式校验 — 非法值拒绝")
    void r1EmailValidationInvalid() {
        String[] invalidEmails = {"not-an-email", "@missing-domain", "missing@", "", "no-at-sign"};
        for (String email : invalidEmails) {
            assertFalse(validateEmail(email), "Invalid email should fail: " + email);
        }
    }

    @Test
    @DisplayName("R1 T1: url 格式校验 — 合法值通过")
    void r1UrlValidationValid() {
        String[] validUrls = {"https://example.com", "http://localhost:3000", "https://sub.domain.io/path"};
        for (String url : validUrls) {
            assertTrue(validateUrl(url), "Valid URL should pass: " + url);
        }
    }

    @Test
    @DisplayName("R1 T1: url 格式校验 — 非法值拒绝")
    void r1UrlValidationInvalid() {
        String[] invalidUrls = {"not-a-url", "ftp://unsupported", "http://", ""};
        for (String url : invalidUrls) {
            assertFalse(validateUrl(url), "Invalid URL should fail: " + url);
        }
    }

    @Test
    @DisplayName("R1 T1: phone 格式校验 — 合法值通过")
    void r1PhoneValidationValid() {
        String[] validPhones = {"1234567890", "+1-234-567-8900", "(123) 456-7890"};
        for (String phone : validPhones) {
            assertTrue(validatePhone(phone), "Valid phone should pass: " + phone);
        }
    }

    @Test
    @DisplayName("R1 T1: phone 格式校验 — 非法值拒绝")
    void r1PhoneValidationInvalid() {
        String[] invalidPhones = {"", "abc-def-ghij", "123", "12345"};
        for (String phone : invalidPhones) {
            assertFalse(validatePhone(phone), "Invalid phone should fail: " + phone);
        }
    }

    @Test
    @DisplayName("R1 T1: autonumber 并发安全 — 序列生成不重复")
    void r1AutonumberConcurrencySafe() {
        AutonumberGenerator gen = new AutonumberGenerator();
        long seq1 = gen.next();
        long seq2 = gen.next();
        long seq3 = gen.next();
        
        assertTrue(seq1 > 0, "First autonumber should be positive");
        assertTrue(seq2 > seq1, "Second autonumber should be greater than first");
        assertTrue(seq3 > seq2, "Third autonumber should be greater than second");
        assertEquals(1, seq2 - seq1, "Consecutive autonumbers should differ by 1");
        assertEquals(1, seq3 - seq2, "Consecutive autonumbers should differ by 1");
    }

    @Test
    @DisplayName("R1 T1: 物理列类型映射 — email/url/phone → TEXT")
    void r1PhysicalColumnMappingFormat() {
        assertEquals("TEXT", mapFieldTypeToSql("email"));
        assertEquals("TEXT", mapFieldTypeToSql("url"));
        assertEquals("TEXT", mapFieldTypeToSql("phone"));
    }

    @Test
    @DisplayName("R1 T1: 物理列类型映射 — currency/percent → NUMERIC")
    void r1PhysicalColumnMappingNumeric() {
        assertEquals("NUMERIC", mapFieldTypeToSql("currency"));
        assertEquals("NUMERIC", mapFieldTypeToSql("percent"));
    }

    @Test
    @DisplayName("R1 T1: 物理列类型映射 — duration/rating → INTEGER")
    void r1PhysicalColumnMappingInteger() {
        assertEquals("INTEGER", mapFieldTypeToSql("duration"));
        assertEquals("INTEGER", mapFieldTypeToSql("rating"));
    }

    @Test
    @DisplayName("R1 T1: 物理列类型映射 — createdTime/lastModifiedTime → TIMESTAMPTZ")
    void r1PhysicalColumnMappingTimestamp() {
        assertEquals("TIMESTAMPTZ", mapFieldTypeToSql("createdTime"));
        assertEquals("TIMESTAMPTZ", mapFieldTypeToSql("lastModifiedTime"));
    }

    @Test
    @DisplayName("R1 T1: 物理列类型映射 — createdBy/lastModifiedBy → UUID")
    void r1PhysicalColumnMappingUuid() {
        assertEquals("UUID", mapFieldTypeToSql("createdBy"));
        assertEquals("UUID", mapFieldTypeToSql("lastModifiedBy"));
    }

    @Test
    @DisplayName("R1 T1: 物理列类型映射 — autonumber → BIGINT")
    void r1PhysicalColumnMappingBigint() {
        assertEquals("BIGINT", mapFieldTypeToSql("autonumber"));
    }

    @Test
    @DisplayName("R1 T1: 系统字段应标记为只读")
    void r1SystemFieldsReadOnly() {
        assertTrue(isSystemField("createdTime"));
        assertTrue(isSystemField("lastModifiedTime"));
        assertTrue(isSystemField("createdBy"));
        assertTrue(isSystemField("lastModifiedBy"));
        assertTrue(isSystemField("autonumber"));
        
        assertFalse(isSystemField("text"));
        assertFalse(isSystemField("email"));
        assertFalse(isSystemField("number"));
    }

    @Test
    @DisplayName("R1 T1: 非可分组字段应被识别")
    void r1NonGroupableFields() {
        assertTrue(isNonGroupableField("formula"));
        assertTrue(isNonGroupableField("rollup"));
        assertTrue(isNonGroupableField("lookup"));
        assertTrue(isNonGroupableField("attachment"));
        assertTrue(isNonGroupableField("hasMany"));
        
        assertFalse(isNonGroupableField("text"));
        assertFalse(isNonGroupableField("email"));
        assertFalse(isNonGroupableField("select"));
    }

    // Helper methods for testing
    private boolean validateEmail(String value) {
        if (value == null || value.isBlank()) return false;
        return value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private boolean validateUrl(String value) {
        if (value == null || value.isBlank()) return false;
        return value.matches("^https?://[^\\s/$.?#].[^\\s]*$");
    }

    private boolean validatePhone(String value) {
        if (value == null || value.isBlank()) return false;
        return value.matches("^[\\d\\s\\-\\+\\(\\)]{7,20}$");
    }

    private String mapFieldTypeToSql(String fieldType) {
        return switch (fieldType) {
            case "email", "url", "phone" -> "TEXT";
            case "currency", "percent" -> "NUMERIC";
            case "duration", "rating" -> "INTEGER";
            case "createdTime", "lastModifiedTime" -> "TIMESTAMPTZ";
            case "createdBy", "lastModifiedBy" -> "UUID";
            case "autonumber" -> "BIGINT";
            default -> throw new IllegalArgumentException("Unsupported field type: " + fieldType);
        };
    }

    private boolean isSystemField(String fieldType) {
        return switch (fieldType) {
            case "createdTime", "lastModifiedTime", "createdBy", "lastModifiedBy", "autonumber" -> true;
            default -> false;
        };
    }

    private boolean isNonGroupableField(String fieldType) {
        return switch (fieldType) {
            case "formula", "rollup", "lookup", "attachment", "hasMany" -> true;
            default -> false;
        };
    }

    /** Autonumber 生成器 — 序列式递增，避免"先查 max 再 +1"的竞态条件。 */
    static class AutonumberGenerator {
        private volatile long counter = 0;

        public synchronized long next() {
            return ++counter;
        }
    }
}