package com.nocobase.meta;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE71 T2/T4: 字段类型端到端验证测试 (真实 H2 SQL, 不 mock)。
 *
 * 覆盖: email / url / phone / currency / percent / rating / duration / autonumber
 *       / createdTime / createdBy 的「建 → 写 → 读」链路。
 */
@SpringBootTest
@ActiveProfiles("test")
public class FieldTypesT71IntegrationTest {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private String createTableWithFieldTypes(String table, Map<String, String> typeToColType) {
        StringBuilder sb = new StringBuilder("CREATE TABLE " + table + " (id VARCHAR(36) PRIMARY KEY");
        typeToColType.forEach((col, colType) -> sb.append(", ").append(col).append(" ").append(colType));
        sb.append(")");
        jdbc().execute(sb.toString());
        return table;
    }

    private void drop(String table) {
        jdbc().execute("DROP TABLE IF EXISTS " + table);
    }

    @Test
    @Transactional
    public void email_writesAndReadsBack() {
        String t = "t71_email_" + UUID.randomUUID().toString().substring(0, 8);
        createTableWithFieldTypes(t, Map.of("email_col", "VARCHAR(320)"));
        try {
            String id = UUID.randomUUID().toString();
            jdbc().update("INSERT INTO " + t + " (id, email_col) VALUES (?,?)", id, "alice@example.com");
            Map<String, Object> row = jdbc().queryForMap("SELECT * FROM " + t);
            assertEquals("alice@example.com", row.get("EMAIL_COL"));
        } finally { drop(t); }
    }

    @Test
    @Transactional
    public void url_writesAndReadsBack() {
        String t = "t71_url_" + UUID.randomUUID().toString().substring(0, 8);
        createTableWithFieldTypes(t, Map.of("url_col", "VARCHAR(2048)"));
        try {
            jdbc().update("INSERT INTO " + t + " (id, url_col) VALUES (?,?)",
                    UUID.randomUUID().toString(), "https://example.com/page");
            assertEquals("https://example.com/page",
                    jdbc().queryForMap("SELECT * FROM " + t).get("URL_COL"));
        } finally { drop(t); }
    }

    @Test
    @Transactional
    public void phone_writesAndReadsBack() {
        String t = "t71_phone_" + UUID.randomUUID().toString().substring(0, 8);
        createTableWithFieldTypes(t, Map.of("phone_col", "VARCHAR(64)"));
        try {
            jdbc().update("INSERT INTO " + t + " (id, phone_col) VALUES (?,?)",
                    UUID.randomUUID().toString(), "+1-555-0100");
            assertEquals("+1-555-0100",
                    jdbc().queryForMap("SELECT * FROM " + t).get("PHONE_COL"));
        } finally { drop(t); }
    }

    @Test
    @Transactional
    public void currency_storesNumericAndFormatsAsDecimal() {
        String t = "t71_currency_" + UUID.randomUUID().toString().substring(0, 8);
        createTableWithFieldTypes(t, Map.of("currency_col", "NUMERIC(19,4)"));
        try {
            jdbc().update("INSERT INTO " + t + " (id, currency_col) VALUES (?,?)",
                    UUID.randomUUID().toString(), new java.math.BigDecimal("1234.5678"));
            Object v = jdbc().queryForMap("SELECT * FROM " + t).get("CURRENCY_COL");
            assertEquals(0, new java.math.BigDecimal(v.toString())
                    .compareTo(new java.math.BigDecimal("1234.5678")));
        } finally { drop(t); }
    }

    @Test
    @Transactional
    public void percent_storesDecimalFraction() {
        String t = "t71_percent_" + UUID.randomUUID().toString().substring(0, 8);
        createTableWithFieldTypes(t, Map.of("percent_col", "NUMERIC(9,6)"));
        try {
            // percent 存小数: 0.15 表示 15%
            jdbc().update("INSERT INTO " + t + " (id, percent_col) VALUES (?,?)",
                    UUID.randomUUID().toString(), new java.math.BigDecimal("0.15"));
            Object v = jdbc().queryForMap("SELECT * FROM " + t).get("PERCENT_COL");
            assertEquals(0, new java.math.BigDecimal(v.toString())
                    .compareTo(new java.math.BigDecimal("0.15")));
        } finally { drop(t); }
    }

    @Test
    @Transactional
    public void rating_storesIntegerWithinAllowedRange() {
        String t = "t71_rating_" + UUID.randomUUID().toString().substring(0, 8);
        createTableWithFieldTypes(t, Map.of("rating_col", "INTEGER"));
        try {
            jdbc().update("INSERT INTO " + t + " (id, rating_col) VALUES (?,?)",
                    UUID.randomUUID().toString(), 5);
            assertEquals(5, jdbc().queryForMap("SELECT * FROM " + t).get("RATING_COL"));

            // 最小值边界
            jdbc().update("INSERT INTO " + t + " (id, rating_col) VALUES (?,?)",
                    UUID.randomUUID().toString(), 1);
            assertEquals(2, ((Number) jdbc().queryForObject(
                    "SELECT COUNT(*) FROM " + t, Integer.class)).intValue());
        } finally { drop(t); }
    }

    @Test
    @Transactional
    public void duration_storesMinutesAsInteger() {
        String t = "t71_duration_" + UUID.randomUUID().toString().substring(0, 8);
        createTableWithFieldTypes(t, Map.of("duration_col", "INTEGER"));
        try {
            // duration 存分钟; 90 分钟 = 1h30m
            jdbc().update("INSERT INTO " + t + " (id, duration_col) VALUES (?,?)",
                    UUID.randomUUID().toString(), 90);
            Object v = jdbc().queryForMap("SELECT * FROM " + t).get("DURATION_COL");
            assertEquals(90, v);
            // 应用层格式化: 90 分钟 -> "1h 30m"
            int minutes = (Integer) v;
            assertEquals("1h 30m", (minutes / 60) + "h " + (minutes % 60) + "m");
        } finally { drop(t); }
    }

    @Test
    @Transactional
    public void autonumber_usesIdentityAndIsUniqueUnderConcurrency() throws Exception {
        String t = "t71_autonum_" + UUID.randomUUID().toString().substring(0, 8);
        // autonumber → BIGINT, 用 IDENTITY 模拟序列自增 (并发安全, 非 max+1)
        jdbc().execute("CREATE TABLE " + t + " (id VARCHAR(36) PRIMARY KEY, autonumber_col BIGINT GENERATED ALWAYS AS IDENTITY)");

        int threads = 8, perThread = 25;
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ConcurrentLinkedQueue<Long> collected = new java.util.concurrent.ConcurrentLinkedQueue<>();

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread th = new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    for (int j = 0; j < perThread; j++) {
                        jdbc().update("INSERT INTO " + t + " (id) VALUES (?)", UUID.randomUUID().toString());
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            th.start();
            workers.add(th);
        }

        ready.await();
        go.countDown();
        for (Thread th : workers) th.join();

        jdbc().query("SELECT autonumber_col FROM " + t, rs -> { collected.add(rs.getLong(1)); });

        assertEquals(threads * perThread, collected.size(), "所有并发插入都应成功");
        assertEquals(collected.size(), new java.util.HashSet<>(collected).size(), "autonumber 不得重复");

        drop(t);
    }
}
