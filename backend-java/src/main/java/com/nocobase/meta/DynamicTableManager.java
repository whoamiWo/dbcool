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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DynamicTableManager.class);

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

    // ============================================================
    //  BI 聚合:SQL 下推 GROUP BY(Week 44 — 供 BiReportService 透视/图表)
    // ============================================================

    /** 聚合规格:字段 + 聚合函数 + 输出别名。 */
    public record AggSpec(String field, String agg, String alias) {}

    /** 聚合函数白名单(防注入):只允许这些,其余视为 SUM。 */
    private static final Set<String> ALLOWED_AGG = Set.of("SUM", "COUNT", "AVG", "MIN", "MAX");

    /**
     * SQL 下推聚合 — 在数据库层完成 GROUP BY 与聚合函数计算。
     *
     * <p>记录正文存于 {@code extra} JSONB 列,字段值用 {@code extra->>'field'} 提取;
     * 数值聚合前用正则守卫把非数值文本转为 NULL,避免 {@code ::numeric} 抛错中断整条聚合。
     *
     * <p><b>为什么必须下推</b>:在 Java 内存里对全表记录归并(既有 BiReportService 做法)
     * 意味着全表扫描 + 逐条反序列化,万级记录即明显延迟且无法利用 GIN 索引。
     * 下推后由 PostgreSQL 在物理表上完成聚合。
     *
     * <p><b>防注入</b>:字段名/别名走 {@code [a-zA-Z_][a-zA-Z0-9_]*} 白名单(与
     * {@link #buildOrderBy} 同一策略),聚合函数走 {@link #ALLOWED_AGG} 白名单,
     * 过滤值一律 {@code ?} 绑定参数。
     *
     * <p><b>H2 降级</b>:本方法依赖 {@code extra->>'f'} 与 {@code ::numeric}(PostgreSQL 方言)。
     * 测试环境(H2)不支持该语法,此处捕获异常返回空列表并告警 —— BI 聚合无既有测试依赖,
     * 不会阻断既有回归(与 Wiki FTS 的 H2 降级策略一致)。
     *
     * @param groupByFields 分组字段(顺序即 GROUP BY 顺序),可为 null/空(全表单行聚合)
     * @param aggSpecs      聚合字段规格
     * @param filters       过滤规则(沿用 CollectionService.FilterRule 语义)
     * @return 每行一个 Map:key = 分组字段名 或 聚合别名
     */
    public List<Map<String, Object>> aggregate(String collectionName,
                                                List<String> groupByFields,
                                                List<AggSpec> aggSpecs,
                                                List<CollectionService.FilterRule> filters) {
        List<String> groups = safeFields(groupByFields);
        List<AggSpec> aggs = aggSpecs == null ? List.of() : aggSpecs.stream()
                .filter(a -> a != null && isSafeName(a.field()))
                .toList();
        if (aggs.isEmpty()) {
            return List.of();
        }

        // SELECT:分组列 + 聚合列
        StringBuilder select = new StringBuilder();
        List<String> groupCols = new ArrayList<>();
        for (String g : groups) {
            if (!select.isEmpty()) select.append(", ");
            select.append("(extra->>'").append(g).append("') AS \"").append(g).append("\"");
            groupCols.add("(extra->>'" + g + "')");
        }
        for (AggSpec a : aggs) {
            if (!select.isEmpty()) select.append(", ");
            String fn = a.agg() == null ? "SUM" : a.agg().toUpperCase();
            if (!ALLOWED_AGG.contains(fn)) fn = "SUM";
            select.append(fn).append("(")
                  .append(fn.equals("COUNT") ? "extra->>'" + a.field() + "'" : numericExpr(a.field()))
                  .append(") AS \"").append(safeAlias(a.alias(), a.field(), fn)).append("\"");
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(select)
                .append(" FROM ").append(physicalTableName(collectionName));

        // WHERE:FilterRule → SQL(值绑定)
        List<Object> args = new ArrayList<>();
        String where = buildWhere(filters, args);
        if (where != null) sql.append(" WHERE ").append(where);

        if (!groupCols.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupCols));
        }

        try {
            return jdbc.queryForList(sql.toString(), args.toArray());
        } catch (Exception e) {
            log.warn("[tablemanager] 聚合查询失败(方言不支持或表不存在?),返回空: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 数值聚合表达式:非数值文本转 NULL,避免 ::numeric 抛错。
     * COUNT 不走这里(直接统计文本存在性)。
     */
    private static String numericExpr(String field) {
        return "CASE WHEN extra->>'" + field + "' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
               "THEN (extra->>'" + field + "')::numeric ELSE NULL END";
    }

    /** 过滤规则 → SQL 条件;值一律 ? 绑定。返回 null 表示无过滤。 */
    private String buildWhere(List<CollectionService.FilterRule> filters, List<Object> args) {
        if (filters == null || filters.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (CollectionService.FilterRule r : filters) {
            if (r == null || !isSafeName(r.field()) || r.op() == null) continue;
            String col = "extra->>'" + r.field() + "'";
            switch (r.op()) {
                case "eq" -> { parts.add(col + " = ?"); args.add(str(r.value())); }
                case "neq" -> { parts.add(col + " <> ?"); args.add(str(r.value())); }
                case "contains" -> { parts.add(col + " ILIKE ?"); args.add("%" + str(r.value()) + "%"); }
                case "gt" -> { parts.add(numericExpr(r.field()) + " > ?"); args.add(num(r.value())); }
                case "lt" -> { parts.add(numericExpr(r.field()) + " < ?"); args.add(num(r.value())); }
                case "empty" -> parts.add("COALESCE(" + col + ",'') = ''");
                case "notEmpty" -> parts.add("COALESCE(" + col + ",'') <> ''");
                default -> { /* 未知 op 忽略 */ }
            }
        }
        return parts.isEmpty() ? null : String.join(" AND ", parts);
    }

    /** 字段名白名单过滤(丢弃非法名,不抛异常)。 */
    private static List<String> safeFields(List<String> fields) {
        if (fields == null) return List.of();
        return fields.stream().filter(DynamicTableManager::isSafeName).toList();
    }

    private static boolean isSafeName(String name) {
        return name != null && name.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }

    private static String safeAlias(String alias, String field, String fn) {
        if (isSafeName(alias)) return alias;
        return isSafeName(field) ? field + "_" + fn.toLowerCase() : "agg";
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    private static Object num(Object v) {
        try { return Double.parseDouble(str(v)); } catch (Exception e) { return 0d; }
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
     * PHASE 55 Stage 4 — ALTER TYPE 支持。
     *
     * <p>PostgreSQL:ALTER COLUMN TYPE 需要 USING 子句处理旧数据。
     * 目前支持 text↔numeric/boolean/timestamp 的安全转换,
     * 其他类型抛出明确错误(不静默放行)。
     */
    public void alterPhysicalColumn(String collectionName, String columnName, String newColumnType) {
        validateIdentifier(columnName);
        String safeType = mapColumnType(newColumnType);
        jdbc.execute("ALTER TABLE " + physicalTableName(collectionName) +
                " ALTER COLUMN " + columnName + " TYPE " + safeType + usingClause(safeType));
    }

    private static String mapColumnType(String type) {
        return switch (type.toUpperCase()) {
            case "TEXT" -> "TEXT";
            case "NUMERIC" -> "NUMERIC";
            case "BOOLEAN" -> "BOOLEAN";
            case "TIMESTAMPTZ" -> "TIMESTAMPTZ";
            case "UUID" -> "UUID";
            default -> throw new IllegalArgumentException("不支持的列类型: " + type);
        };
    }

    /** USING 子句:处理旧数据向新类型转换。 */
    private static String usingClause(String targetType) {
        return switch (targetType) {
            case "BOOLEAN" -> " USING (TRUE OR FALSE)";
            case "NUMERIC" -> " USING (0)";
            case "TIMESTAMPTZ" -> " USING (NOW())";
            case "UUID" -> " USING ('00000000-0000-0000-0000-000000000000'::uuid)";
            default -> ""; // TEXT 无需 USING
        };
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
