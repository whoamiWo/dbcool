package com.nocobase.meta;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 动态表管理 — Week 7 扩展支持 ALTER.
 *
 * <p>Week 5 MVP:创建表 + 插入/查询.
 * Week 7+:增/删/改字段(混合方案 C:基础字段物理列 + 动态字段 JSONB).
 */
@Component
public class DynamicTableManager {

    private final JdbcTemplate jdbc;

    public DynamicTableManager(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 创建 collection 的物理表.
     */
    public void createTable(String collectionName) {
        String sql = """
                CREATE TABLE IF NOT EXISTS %s (
                    id UUID PRIMARY KEY,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    created_by UUID,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_by UUID,
                    extra JSONB NOT NULL DEFAULT '{}'::jsonb
                )
                """.formatted(physicalTableName(collectionName));
        jdbc.execute(sql);
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_" + collectionName + "_extra " +
                "ON " + physicalTableName(collectionName) + " USING GIN (extra jsonb_path_ops)"
        );
    }

    /**
     * 删除 collection 的物理表.
     */
    public void dropTable(String collectionName) {
        jdbc.execute("DROP TABLE IF EXISTS " + physicalTableName(collectionName));
    }

    /**
     * 校验 collection 是否存在(物理表).
     */
    public boolean tableExists(String collectionName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_name = ? AND table_schema = 'public'",
                Integer.class,
                physicalTableName(collectionName)
        );
        return count != null && count > 0;
    }

    /**
     * 插入一条记录.
     */
    public void insertRecord(String collectionName, String id, String jsonData) {
        jdbc.update(
                "INSERT INTO " + physicalTableName(collectionName) +
                " (id, extra) VALUES (?::uuid, ?::jsonb)",
                id, jsonData
        );
    }

    /**
     * 列出记录.
     */
    public List<String> listRecords(String collectionName, int limit) {
        return jdbc.queryForList(
                "SELECT extra::text FROM " + physicalTableName(collectionName) +
                " ORDER BY created_at DESC LIMIT ?",
                String.class,
                Math.min(limit, 200)
        );
    }

    /**
     * 按 ID 取单条记录的 extra JSON(Week 14.5 P3-3 补完).
     */
    public java.util.Optional<String> getRecord(String collectionName, String id) {
        var list = jdbc.queryForList(
                "SELECT extra::text FROM " + physicalTableName(collectionName) +
                " WHERE id = ?::uuid",
                String.class,
                id
        );
        return list.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(list.get(0));
    }

    /**
     * 更新记录 extra JSON(整段覆盖).
     */
    public int updateRecord(String collectionName, String id, String jsonData) {
        return jdbc.update(
                "UPDATE " + physicalTableName(collectionName) +
                " SET extra = ?::jsonb, updated_at = NOW() WHERE id = ?::uuid",
                jsonData, id
        );
    }

    /**
     * 删除单条记录.
     */
    public int deleteRecord(String collectionName, String id) {
        return jdbc.update(
                "DELETE FROM " + physicalTableName(collectionName) +
                " WHERE id = ?::uuid",
                id
        );
    }

    // ============================================================
    //  Week 7+:字段变更(US-005)
    // ============================================================

    /**
     * 添加物理列.
     */
    public void addPhysicalColumn(String collectionName, String columnName, String columnType) {
        validateIdentifier(columnName);
        jdbc.execute("ALTER TABLE " + physicalTableName(collectionName) +
                " ADD COLUMN " + columnName + " " + columnType);
    }

    /**
     * 删除物理列.
     */
    public void dropPhysicalColumn(String collectionName, String columnName) {
        validateIdentifier(columnName);
        jdbc.execute("ALTER TABLE " + physicalTableName(collectionName) +
                " DROP COLUMN " + columnName);
    }

    /**
     * 重命名物理列.
     */
    public void renamePhysicalColumn(String collectionName, String oldName, String newName) {
        validateIdentifier(oldName);
        validateIdentifier(newName);
        jdbc.execute("ALTER TABLE " + physicalTableName(collectionName) +
                " RENAME COLUMN " + oldName + " TO " + newName);
    }

    /**
     * 设置 lock_timeout(防止大表 ALTER 阻塞太久).
     */
    public void setLockTimeout(int milliseconds) {
        jdbc.execute("SET lock_timeout = '" + milliseconds + "ms'");
    }

    /**
     * 重置 lock_timeout.
     */
    public void resetLockTimeout() {
        jdbc.execute("SET lock_timeout = '0'");
    }

    /**
     * 拿当前表的所有字段(包括物理列 + JSONB key).
     */
    public List<Map<String, Object>> getColumns(String collectionName) {
        return jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_name = ? AND table_schema = 'public'",
                physicalTableName(collectionName)
        );
    }

    public static String physicalTableName(String collectionName) {
        return "data_" + collectionName;
    }

    private static void validateIdentifier(String name) {
        if (name == null || !name.matches("^[a-z_][a-z0-9_]{0,63}$")) {
            throw new IllegalArgumentException("非法标识符: " + name);
        }
    }
}
