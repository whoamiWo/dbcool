package com.nocobase.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** R11: AlertStore (SQLite + JdbcTemplate) 单元测试. */
class AlertStoreTest {

    @TempDir
    Path tempDir;

    private AlertStore store;
    private Path dbFile;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tempDir.resolve("alerts-" + System.nanoTime() + ".db");
        // 用一个空 JdbcTemplate(此 store 主要用 raw connection,不依赖 JdbcTemplate)
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource("jdbc:sqlite:" + dbFile, "", "")
        );
        store = new AlertStore(jdbc, dbFile.toString());
        store.init();
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(dbFile);
    }

    @Test
    void insertAndIterate() {
        var ev = new AlertEvent("rate_limit_exceeded", "u1", Map.of("limit", 30));
        store.insertAlert(ev);

        var rows = store.iterAlerts(10, true, null);
        assertEquals(1, rows.size());
        assertEquals(ev.id(), rows.get(0).get("id"));
        assertEquals("rate_limit_exceeded", rows.get(0).get("kind"));
        assertEquals("u1", rows.get(0).get("user_id"));
    }

    @Test
    void updateAck() {
        var ev = new AlertEvent("test", null, null);
        store.insertAlert(ev);
        boolean updated = store.updateAlert(
                ev.id(), true, "admin", System.currentTimeMillis() / 1000.0,
                null, null
        );
        assertTrue(updated);
        var rows = store.iterAlerts(10, true, null);
        assertEquals(true, rows.get(0).get("acked"));
        assertEquals("admin", rows.get(0).get("acked_by"));
    }

    @Test
    void updateResolve() {
        var ev = new AlertEvent("test", null, null);
        store.insertAlert(ev);
        store.updateAlert(ev.id(), null, null, null, true, 999.0);
        var rows = store.iterAlerts(10, true, null);
        assertEquals(true, rows.get(0).get("resolved"));
        // includeResolved=false 过滤
        var unresolved = store.iterAlerts(10, false, null);
        assertEquals(0, unresolved.size());
    }

    @Test
    void countByKind() {
        store.insertAlert(new AlertEvent("a", null, null));
        store.insertAlert(new AlertEvent("a", null, null));
        store.insertAlert(new AlertEvent("b", null, null));
        var counts = store.countAlerts(true);
        assertEquals(2, counts.get("a"));
        assertEquals(1, counts.get("b"));
    }

    @Test
    void unresolvedCount() {
        var ev1 = new AlertEvent("test", null, null);
        var ev2 = new AlertEvent("test", null, null);
        store.insertAlert(ev1);
        store.insertAlert(ev2);
        assertEquals(2, store.unresolvedCount());
        store.updateAlert(ev1.id(), null, null, null, true, 0.0);
        assertEquals(1, store.unresolvedCount());
    }

    @Test
    void filterByKinds() {
        store.insertAlert(new AlertEvent("a", null, null));
        store.insertAlert(new AlertEvent("b", null, null));
        store.insertAlert(new AlertEvent("c", null, null));
        var rows = store.iterAlerts(10, true, List.of("a", "b"));
        assertEquals(2, rows.size());
    }

    @Test
    void subscribeAndUnsubscribe() {
        assertTrue(store.subscribe("u1", "rate_limit_exceeded"));
        assertFalse(store.subscribe("u1", "rate_limit_exceeded")); // 重复
        assertEquals(List.of("rate_limit_exceeded"), store.subscriptionsFor("u1"));
        assertEquals(List.of("u1"), store.subscribersFor("rate_limit_exceeded"));
        assertTrue(store.unsubscribe("u1", "rate_limit_exceeded"));
        assertEquals(List.of(), store.subscriptionsFor("u1"));
    }

    @Test
    void multipleUsersSubscriptions() {
        store.subscribe("u1", "a");
        store.subscribe("u2", "b");
        store.subscribe("u3", "a");
        assertEquals(2, store.subscribersFor("a").size());
        assertEquals(1, store.subscribersFor("b").size());
    }

    @Test
    void persistenceAcrossReinit() throws Exception {
        var ev = new AlertEvent("persist", "u1", Map.of("k", "v"));
        store.insertAlert(ev);

        // 重新初始化(模拟重启)
        var jdbc2 = new JdbcTemplate(
                new DriverManagerDataSource("jdbc:sqlite:" + dbFile, "", "")
        );
        var store2 = new AlertStore(jdbc2, dbFile.toString());
        store2.init();
        var rows = store2.iterAlerts(10, true, null);
        assertEquals(1, rows.size());
        assertEquals("persist", rows.get(0).get("kind"));
    }

    @Test
    void getPathReturnsConfigured() {
        assertNotNull(store.getPath());
        assertEquals(dbFile.toString(), store.getPath());
    }
}
