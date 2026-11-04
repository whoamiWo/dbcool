package com.nocobase.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 部门数据范围解析器（A-1 修复的核心）。
 *
 * <p><b>修复前的问题</b>（backend {@code AclEnforcer.resolveDataScopeSql} DEPT 分支）：
 * <ol>
 *   <li>SQL 引用不存在的表 {@code user_u}（应为 {@code users}）—— 生成的片段一旦下推，
 *       每次查询都会报表不存在错误；之前的单测只断言"字符串含 department_id"，
 *       于是带着坏 SQL 照样绿灯（mock 掩盖典型）。</li>
 *   <li>"同部门"被实现成 {@code department_id 相等}，不是部门<b>子树</b>——
 *       组织语义里"华东大区（含下属分部）"的部门负责人应能看到整个子树的记录。</li>
 *   <li>内存过滤分支 {@code record.get("department_id") != null} 语义完全错误：
 *       业务记录根本没有 department_id 列，恒 false 时整张表不可见，恒 true 时全员可见。</li>
 * </ol>
 *
 * <p><b>现在的语义</b>（任务书 A-1）：DEPT = 用户所在部门的<b>整棵子树</b>内的成员
 * 所创建的记录可见。子树通过 {@code org_unit.path} 物化路径前缀匹配
 * （{@code LIKE :prefix || '%'}），与 {@code users.department_id} 联查得到「可见创建者集合」，
 * 再由调用方做行级匹配。用户无部门 → 降级为 SELF（只能看自己的）。
 *
 * <p>查询走只读 {@link JdbcTemplate}，不做 SQL 片段拼接下推 —— 子树集合一次算出后
 * 由 Java 侧逐行匹配，避免把动态 SQL 片段拼接进任意 collection 的查询
 * （那里的标识符校验只覆盖 collection/field 名，兜不住子查询文本）。
 */
@Service
public class DeptScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(DeptScopeResolver.class);

    private final JdbcTemplate jdbc;

    public DeptScopeResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 解析某用户的可见创建者集合（DEPT 范围）。
     *
     * @return 成员 id 集合（至少含用户自己）；DB 异常时返回「仅本人」—— 保守收敛
     */
    public Set<UUID> resolveVisibleCreatorIds(UUID userId, String tenantId) {
        Set<UUID> result = new HashSet<>();
        if (userId == null) return result;
        result.add(userId);
        try {
            String path = resolveUserDeptPath(userId, tenantId);
            if (path == null) {
                // 无部门 → SELF 兜底（决策 D6）
                return result;
            }
            // 子树 = path 前缀匹配的所有部门下的所有成员
            List<UUID> ids = jdbc.query(
                    "SELECT u.id FROM users u JOIN org_unit o ON u.department_id = o.id "
                            + "WHERE o.tenant_id = ? AND o.deleted = FALSE AND o.path LIKE ? || '%'",
                    (rs, i) -> UUID.fromString(rs.getString(1)),
                    tenantId, path);
            result.addAll(ids);
        } catch (DataAccessException e) {
            // fail-safe：部门解析失败时收敛到 SELF（否则可能全员可见 = 越权）
            log.error("[acl] DEPT 子树解析失败，收敛为 SELF userId={} tenant={}: {}",
                    userId, tenantId, e.getMessage());
            result.retainAll(Set.of(userId));
        }
        return result;
    }

    /**
     * 用户所在部门的物化路径（{@code org_unit.path}）。
     *
     * @return path；无部门/部门已删/异常 → null
     */
    public String resolveUserDeptPath(UUID userId, String tenantId) {
        try {
            List<String> paths = jdbc.query(
                    "SELECT o.path FROM org_unit o JOIN users u ON u.department_id = o.id "
                            + "WHERE u.id = ? AND o.tenant_id = ? AND o.deleted = FALSE",
                    (rs, i) -> rs.getString(1),
                    userId, tenantId);
            return paths.isEmpty() ? null : paths.get(0);
        } catch (DataAccessException e) {
            log.error("[acl] 用户部门路径查询失败 userId={} tenant={}: {}",
                    userId, tenantId, e.getMessage());
            return null;
        }
    }

    /**
     * 生成 DEPT 范围的下推 SQL 谓词（供报表/审计等确实需要 SQL 文本的场合）。
     *
     * <p><b>与修复前的差异</b>：表名是真实存在的 {@code users} / {@code org_unit}，
     * 子树用 {@code path LIKE :deptPath || '%'} 表达。
     *
     * @param creatorField 业务表中归属人列名（白名单校验由调用方负责）
     * @return 谓词 SQL；用户无部门时返回 null（调用方应按 SELF 处理）
     */
    public String buildDeptScopeSql(UUID userId, String tenantId, String creatorField) {
        String path = resolveUserDeptPath(userId, tenantId);
        if (path == null) return null;
        String field = (creatorField == null || creatorField.isBlank()) ? "created_by" : creatorField;
        return field + " IN (SELECT u.id FROM users u JOIN org_unit o ON u.department_id = o.id "
                + "WHERE o.tenant_id = '" + tenantId.replace("'", "''")
                + "' AND o.deleted = FALSE AND o.path LIKE '" + path.replace("'", "''") + "%')";
    }

    /**
     * 列出一个部门的直接子部门（组织树遍历辅助，供 T2 org API 复用）。
     */
    public List<UUID> childUnitIds(UUID unitId, String tenantId) {
        try {
            return jdbc.query(
                    "SELECT id FROM org_unit WHERE tenant_id = ? AND parent_id = ? AND deleted = FALSE "
                            + "ORDER BY sort, name",
                    (rs, i) -> UUID.fromString(rs.getString(1)),
                    tenantId, unitId);
        } catch (DataAccessException e) {
            log.error("[acl] 子部门查询失败 unitId={}: {}", unitId, e.getMessage());
            return new ArrayList<>();
        }
    }
}
