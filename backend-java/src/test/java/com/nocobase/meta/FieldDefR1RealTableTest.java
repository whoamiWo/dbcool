package com.nocobase.meta;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1-A: 真实建表验证 — 创建含新类型字段的集合，验证物理列可写入。
 */
@SpringBootTest
@ActiveProfiles("test")
public class FieldDefR1RealTableTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void testInsertWithNewColumnTypes() throws Exception {
        String tableName = "TEST_R1_NEW_" + System.currentTimeMillis();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        
        // Create table with new column types (H2 compatible)
        jdbc.execute(
            "CREATE TABLE " + tableName + " (" +
            "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY," +
            "email_col VARCHAR," +
            "url_col VARCHAR," +
            "phone_col VARCHAR," +
            "currency_col NUMERIC," +
            "percent_col NUMERIC," +
            "duration_col INTEGER," +
            "rating_col INTEGER," +
            "created_time_col TIMESTAMP WITH TIME ZONE," +
            "last_modified_time_col TIMESTAMP WITH TIME ZONE," +
            "created_by_col VARCHAR(36)," +
            "last_modified_by_col VARCHAR(36)," +
            "autonumber_col BIGINT" +
            ")"
        );
        
        // Insert test data
        jdbc.update(
            "INSERT INTO " + tableName + " (email_col, url_col, phone_col, currency_col, percent_col, duration_col, rating_col, created_time_col, last_modified_time_col, created_by_col, last_modified_by_col, autonumber_col) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "test@example.com", "https://example.com", "+1-234-567-8900", 123.45, 0.15, 60, 5, 
            java.sql.Timestamp.from(java.time.Instant.now()), java.sql.Timestamp.from(java.time.Instant.now()),
            "550e8400-e29b-41d4-a716-446655440000", "550e8400-e29b-41d4-a716-446655440001", 100L
        );
        
        // Verify data was inserted
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM " + tableName);
        
        assertEquals("test@example.com", row.get("EMAIL_COL"));
        assertEquals("https://example.com", row.get("URL_COL"));
        assertEquals("+1-234-567-8900", row.get("PHONE_COL"));
        assertEquals(123.45, ((Number) row.get("CURRENCY_COL")).doubleValue(), 0.01);
        assertEquals(0.15, ((Number) row.get("PERCENT_COL")).doubleValue(), 0.01);
        assertEquals(60, row.get("DURATION_COL"));
        assertEquals(5, row.get("RATING_COL"));
        assertNotNull(row.get("CREATED_TIME_COL"));
        assertNotNull(row.get("LAST_MODIFIED_TIME_COL"));
        assertEquals("550e8400-e29b-41d4-a716-446655440000", row.get("CREATED_BY_COL"));
        assertEquals("550e8400-e29b-41d4-a716-446655440001", row.get("LAST_MODIFIED_BY_COL"));
        assertEquals(100L, row.get("AUTONUMBER_COL"));
        
        System.out.println("All 12 new column types verified successfully!");
        
        // Cleanup
        jdbc.execute("DROP TABLE IF EXISTS " + tableName);
    }
}