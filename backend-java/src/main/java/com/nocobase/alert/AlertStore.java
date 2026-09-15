package com.nocobase.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * R11: 告警事件 + 订阅的 SQLite 持久化(JdbcTemplate).
 *
 * 通过 sqlite-jdbc 直连 SQLite 文件,不依赖 Spring Data JPA.
 * Spring 启动时自动建表.
 */
@Component
public class AlertStore {

    private static final String SCHEMA_SQL = """
            CREATE TABLE IF NOT EXISTS alerts (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                user_id TEXT,
                detail TEXT NOT NULL DEFAULT '{}',
                ts REAL NOT NULL,
                acked INTEGER NOT NULL DEFAULT 0,
                acked_by TEXT,
                acked_at REAL,
                resolved INTEGER NOT NULL DEFAULT 0,
                resolved_at REAL
            );
            CREATE INDEX IF NOT EXISTS idx_alerts_kind ON alerts(kind);
            CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts);

            CREATE TABLE IF NOT EXISTS alert_subscriptions (
                user_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                created_at REAL NOT NULL,
                PRIMARY KEY (user_id, kind)
            );
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String path;

    public AlertStore(
            JdbcTemplate jdbc,
            @Value("${nocobase.alerts.path:./alerts.db}") String path
    ) {
        this.jdbc = jdbc;
        this.path = path;
    }

    @PostConstruct
    public void init() throws Exception {
        // 确保父目录存在
        File f = new File(path);
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        // 给 JdbcTemplate 设置 SQLite 驱动(若未设置)
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc 缺失", e);
        }
        // 逐条执行建表(SQLite JDBC 的 execute 对多条语句不友好)
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var stmt = conn.createStatement()) {
            for (String s : SCHEMA_SQL.split(";")) {
                String trimmed = s.trim();
                if (trimmed.isEmpty()) continue;
                stmt.execute(trimmed);
            }
        }
    }

    public String getPath() {
        return path;
    }

    // ── 事件 CRUD ─────────────────────────────────────────
    public void insertAlert(AlertEvent ev) {
        String detailJson;
        try {
            detailJson = mapper.writeValueAsString(ev.detail());
        } catch (Exception e) {
            detailJson = "{}";
        }
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path)) {
            var ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO alerts (id, kind, user_id, detail, ts, acked, acked_by, acked_at, resolved, resolved_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            );
            ps.setString(1, ev.id());
            ps.setString(2, ev.kind());
            ps.setString(3, ev.userId());
            ps.setString(4, detailJson);
            ps.setDouble(5, ev.timestamp());
            ps.setInt(6, ev.acked() ? 1 : 0);
            ps.setString(7, ev.ackedBy());
            if (ev.ackedAt() != null) ps.setDouble(8, ev.ackedAt()); else ps.setNull(8, java.sql.Types.REAL);
            ps.setInt(9, ev.resolved() ? 1 : 0);
            if (ev.resolvedAt() != null) ps.setDouble(10, ev.resolvedAt()); else ps.setNull(10, java.sql.Types.REAL);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("insertAlert failed", e);
        }
    }

    public boolean updateAlert(
            String eventId,
            Boolean acked,
            String ackedBy,
            Double ackedAt,
            Boolean resolved,
            Double resolvedAt
    ) {
        StringBuilder sql = new StringBuilder("UPDATE alerts SET ");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        if (acked != null) { sql.append(first ? "" : ", ").append("acked = ?"); params.add(acked ? 1 : 0); first = false; }
        if (ackedBy != null) { sql.append(first ? "" : ", ").append("acked_by = ?"); params.add(ackedBy); first = false; }
        if (ackedAt != null) { sql.append(first ? "" : ", ").append("acked_at = ?"); params.add(ackedAt); first = false; }
        if (resolved != null) { sql.append(first ? "" : ", ").append("resolved = ?"); params.add(resolved ? 1 : 0); first = false; }
        if (resolvedAt != null) { sql.append(first ? "" : ", ").append("resolved_at = ?"); params.add(resolvedAt); first = false; }
        if (first) return false;
        sql.append(" WHERE id = ?");
        params.add(eventId);
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new RuntimeException("updateAlert failed", e);
        }
    }

    public List<Map<String, Object>> iterAlerts(int limit, boolean includeResolved, List<String> kinds) {
        StringBuilder sql = new StringBuilder("SELECT * FROM alerts");
        List<Object> params = new ArrayList<>();
        List<String> where = new ArrayList<>();
        if (!includeResolved) where.add("resolved = 0");
        if (kinds != null && !kinds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(kinds.size(), "?"));
            where.add("kind IN (" + placeholders + ")");
            params.addAll(kinds);
        }
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
        sql.append(" ORDER BY ts DESC LIMIT ?");
        params.add(limit);
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            var rs = ps.executeQuery();
            List<Map<String, Object>> result = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("id", rs.getString("id"));
                row.put("kind", rs.getString("kind"));
                row.put("user_id", rs.getString("user_id"));
                try {
                    row.put("detail", mapper.readValue(rs.getString("detail"), Map.class));
                } catch (Exception e) {
                    row.put("detail", new HashMap<>());
                }
                row.put("timestamp", rs.getDouble("ts"));
                row.put("acked", rs.getInt("acked") == 1);
                row.put("acked_by", rs.getString("acked_by"));
                row.put("acked_at", (Double) rs.getObject("acked_at"));
                row.put("resolved", rs.getInt("resolved") == 1);
                row.put("resolved_at", (Double) rs.getObject("resolved_at"));
                result.add(row);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("iterAlerts failed", e);
        }
    }

    public Map<String, Integer> countAlerts(boolean includeResolved) {
        String sql = "SELECT kind, COUNT(*) AS n FROM alerts"
                + (includeResolved ? "" : " WHERE resolved = 0")
                + " GROUP BY kind";
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            Map<String, Integer> out = new HashMap<>();
            while (rs.next()) out.put(rs.getString("kind"), rs.getInt("n"));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("countAlerts failed", e);
        }
    }

    public int unresolvedCount() {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var ps = conn.prepareStatement("SELECT COUNT(*) AS n FROM alerts WHERE resolved = 0");
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt("n") : 0;
        } catch (Exception e) {
            throw new RuntimeException("unresolvedCount failed", e);
        }
    }

    // ── 订阅 CRUD ─────────────────────────────────────────
    public boolean subscribe(String userId, String kind) {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path)) {
            var ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO alert_subscriptions (user_id, kind, created_at) VALUES (?, ?, ?)"
            );
            ps.setString(1, userId);
            ps.setString(2, kind);
            ps.setDouble(3, System.currentTimeMillis() / 1000.0);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new RuntimeException("subscribe failed", e);
        }
    }

    public boolean unsubscribe(String userId, String kind) {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var ps = conn.prepareStatement(
                     "DELETE FROM alert_subscriptions WHERE user_id = ? AND kind = ?"
             )) {
            ps.setString(1, userId);
            ps.setString(2, kind);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new RuntimeException("unsubscribe failed", e);
        }
    }

    public List<String> subscriptionsFor(String userId) {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var ps = conn.prepareStatement(
                     "SELECT kind FROM alert_subscriptions WHERE user_id = ? ORDER BY created_at"
             )) {
            ps.setString(1, userId);
            var rs = ps.executeQuery();
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString("kind"));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("subscriptionsFor failed", e);
        }
    }

    public List<String> subscribersFor(String kind) {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var ps = conn.prepareStatement(
                     "SELECT user_id FROM alert_subscriptions WHERE kind = ?"
             )) {
            ps.setString(1, kind);
            var rs = ps.executeQuery();
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString("user_id"));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("subscribersFor failed", e);
        }
    }
}
