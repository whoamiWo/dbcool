package com.nocobase.meta;

import com.nocobase.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

    /**
     * 默认 lock_timeout(ms)— 防止单连接慢查询持锁阻塞 ALTER.
     * ALTER 路径在 {@link AsyncMigrationService#executeSync} 中临时覆盖。
     */
    public static final int DEFAULT_LOCK_TIMEOUT_MS = 5000;

    public DynamicTableManager(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @jakarta.annotation.PostConstruct
    void applyDefaultLockTimeout() {
        try {
            setLockTimeout(DEFAULT_LOCK_TIMEOUT_MS);
        } catch (Exception e) {
            // H2 测试环境 SET lock_timeout 可能不支持 — 仅警告,不影响启动
            org.slf4j.LoggerFactory.getLogger(DynamicTableManager.class)
                    .warn("[tablemanager] 应用默认 lock_timeout 设置失败(数据库可能不支持): {}",
                            e.getMessage());
        }
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
                "WHERE table_name = ? AND table_schema = ?",
                Integer.class,
                bareTableName(collectionName),
                TenantContext.currentSchema()
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
     * 列出记录(Week 17: SQL 端支持服务端排序).
     *
     * <p>sortExpr 格式:`"name,-salary"`(逗号分隔,可选 `-` 前缀)。
     * 字段名会被白名单过滤(必须 ∈ fields),不合法字段跳过。
     * 无 sortExpr 时维持原有 ORDER BY created_at DESC。
     */
    public List<String> listRecords(String collectionName, int limit) {
        return listRecords(collectionName, limit, null, null);
    }

    /**
     * 列出记录(Week 17 重载,支持服务端排序白名单).
     *
     * @param sortExpr 逗号分隔字段列表,可加 `-` 前缀表示 DESC,例如 "name,-salary"
     * @param fields   collection 的字段定义(白名单,null=无 sort)
     */
    public List<String> listRecords(String collectionName, int limit,
                                    String sortExpr,
                                    List<com.nocobase.meta.FieldDef> fields) {
        StringBuilder sql = new StringBuilder("SELECT extra::text FROM ")
                .append(physicalTableName(collectionName));
        String orderBy = buildOrderBy(sortExpr, fields);
        if (orderBy != null) {
            sql.append(" ORDER BY ").append(orderBy);
        } else {
            sql.append(" ORDER BY created_at DESC");
        }
        sql.append(" LIMIT ?");
        return jdbc.queryForList(sql.toString(), String.class, Math.min(limit, 500));
    }

    /**
     * 构建安全的 ORDER BY 子句(白名单字段名)。
     *
     * <p>防御:
     * <ul>
     *   <li>字段名只允许 `[a-zA-Z_][a-zA-Z0-9_]*`(正则)— 直接防 SQL injection</li>
     *   <li>`-` / `+` 前缀只允许在首字符</li>
     *   <li>系统字段(created_at/updated_at)+ 任何用户字段(动态 schema)皆可</li>
     * </ul>
     *
     * <p>不再强制 ∈ collection_meta.fields(因为动态 schema 里该数组可能空)。
     *
     * <p><b>package-private</b> 让测试可以正常实例方法调用(反射调用在 JaCoCo 中
     * 不计入覆盖率)。
     */
    String buildOrderBy(String sortExpr,
                         List<com.nocobase.meta.FieldDef> fields) {
        if (sortExpr == null || sortExpr.isBlank()) {
            return null;
        }
        // 字段白名单仅用于「该 collection 是否真的允许排序」(参考性,不强制)
        // 字段名合法性由正则 [a-zA-Z_][a-zA-Z0-9_]* 严格保证 — 这是真正的安全屏障
        List<String> parts = new ArrayList<>();
        for (String token : sortExpr.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            boolean desc = false;
            if (t.startsWith("-")) { desc = true; t = t.substring(1); }
            else if (t.startsWith("+")) { t = t.substring(1); }
            if (!t.matches("[a-zA-Z_][a-zA-Z0-9_]*")) continue;
            // 用 jsonb 提取保证一致性;created_at/updated_at 用原生列
            String col;
            if ("created_at".equals(t) || "updated_at".equals(t)) {
                col = t;
            } else {
                col = "(extra->>'" + t + "')";
            }
            parts.add(col + (desc ? " DESC" : " ASC") + " NULLS LAST");
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * US-003:按字段值统计记录数(用于 unique 约束校验)。
     *
     * <p>记录正文存于 {@code extra} JSONB 列,字段值用 {@code extra->>'field'} 提取。
     *
     * <p><b>防注入</b>:字段名必须匹配 {@code [a-zA-Z_][a-zA-Z0-9_]*},否则直接返回 false
     * (与 {@link #buildOrderBy} 同一安全策略)。值一律走 {@code ?} 绑定参数。
     *
     * @param excludeId 非空时排除该记录(更新场景避免与自身冲突)
     */
    public boolean existsByFieldValue(String collectionName, String fieldName,
                                      String value, String excludeId) {
        if (fieldName == null || !fieldName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return false; // 非法字段名视为无法判定(不误报冲突)
        }
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM ")
                .append(physicalTableName(collectionName))
                .append(" WHERE extra->>'").append(fieldName).append("' = ?");
        List<Object> args = new ArrayList<>();
        args.add(value);
        if (excludeId != null && !excludeId.isBlank()) {
            sql.append(" AND id <> ?::uuid");
            args.add(excludeId);
        }
        try {
            Integer count = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
            return count != null && count > 0;
        } catch (Exception e) {
            // 表不存在 / 列缺失等:不阻断写入,交由上层决定
            return false;
        }
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
                "WHERE table_name = ? AND table_schema = ?",
                bareTableName(collectionName),
                TenantContext.currentSchema()
        );
    }

    /** 物理表名(不含 schema),用于 information_schema 查询。 */
    public static String bareTableName(String collectionName) {
        return "data_" + collectionName;
    }

    /**
     * 物理表全限定名(含 schema),用于 DDL / DML。
     *
     * <p>Week 41 D6 Step G2:动态表按租户 schema 归属 ——
     * 默认租户落 {@code public}(存量不变),其他租户落各自 schema。
     */
    public static String physicalTableName(String collectionName) {
        return TenantContext.currentSchema() + "." + bareTableName(collectionName);
    }

    private static void validateIdentifier(String name) {
        if (name == null || !name.matches("^[a-z_][a-z0-9_]{0,63}$")) {
            throw new IllegalArgumentException("非法标识符: " + name);
        }
    }
}
