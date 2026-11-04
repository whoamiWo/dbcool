package com.nocobase.auth;

import com.nocobase.auth.AclPolicyEntity.DataScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A-1 修复验证：DEPT 数据范围从「假 SQL（user_u 表不存在）+ 假内存判定
 * （department_id != null）」到「真实部门子树可见性」。
 *
 * <p>修复要点（对照 backend AclEnforcer.resolveDataScopeSql / dataScopeSqlMatches）：
 * <ul>
 *   <li>DEPT SQL 引用真实表 users/org_unit，子树用 org_unit.path 前缀表达</li>
 *   <li>无部门 → 收敛为 SELF（决策 D6），不再暗示全员可见</li>
 *   <li>行级过滤按「创建者 ∈ 部门子树成员集合」判定，而非列非空判定</li>
 *   <li>DB 异常 → 保守收敛 SELF（fail-safe，不放权）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AclDataScopeTest {

    @Mock private UserRoleRepository userRoleRepo;
    @Mock private AclPolicyRepository policyRepo;
    @Mock private RoleRepository roleRepo;
    @Mock private JdbcTemplate jdbc;

    private AclEnforcer enforcer;
    private DeptScopeResolver deptResolver;
    private final UUID user = UUID.randomUUID();
    private final String tenant = "tenant_a";

    @BeforeEach
    void setUp() {
        deptResolver = new DeptScopeResolver(jdbc);
        enforcer = new AclEnforcer(userRoleRepo, policyRepo, roleRepo, deptResolver, false);
    }

    private UserRoleEntity userRole(UUID roleId) {
        UserRoleEntity ur = new UserRoleEntity();
        ur.setId(new UserRoleEntity.UserRoleId());
        ur.getId().setUserId(user);
        ur.getId().setRoleId(roleId);
        return ur;
    }

    /** 打桩：user 直接持有一个角色，策略只有一条 data_scope。 */
    private void stubScope(DataScopeType type, String scopeConfigJson) {
        UUID roleId = UUID.randomUUID();
        when(userRoleRepo.findByIdUserId(user)).thenReturn(List.of(userRole(roleId)));
        when(policyRepo.findByRoleIdAndTenantId(roleId, tenant))
                .thenReturn(List.of(newPolicy(roleId, type, scopeConfigJson)));
        when(roleRepo.findSelfAndAncestors(any(), any())).thenReturn(List.of(roleId));
    }

    /** 打桩：用户挂在 /east 分支下，子树成员包括同分支的 alice。 */
    private void stubEastSubtree(UUID aliceId) {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq(user), eq(tenant)))
                .thenReturn(List.of("/east"));
        // 子树成员查询（path LIKE '/east%'）
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq(tenant), eq("/east")))
                .thenReturn(List.of(aliceId));
    }

    @Test
    void noScopePolicy_returnsNull_predicate() {
        stubScope(null, null);

        assertNull(enforcer.resolveDataScopeSql(user, tenant, "created_by"));
        // 无范围约束 → 记录原样返回
        List<Map<String, Object>> records = List.of(Map.of("id", 1));
        assertEquals(1, enforcer.filterRecordsByDataScope(user, tenant, "orders", records).size());
    }

    @Test
    void selfScope_createsCreatedByPredicate() {
        stubScope(DataScopeType.SELF, null);

        String sql = enforcer.resolveDataScopeSql(user, tenant, "created_by");
        assertEquals("created_by = '" + user + "'", sql);

        // 行级过滤：仅本人记录保留
        List<Map<String, Object>> records = List.of(
                Map.of("id", 1, "created_by", user.toString()),
                Map.of("id", 2, "created_by", UUID.randomUUID().toString()));
        List<Map<String, Object>> filtered =
                enforcer.filterRecordsByDataScope(user, tenant, "orders", records);
        assertEquals(1, filtered.size());
        assertEquals(1, filtered.get(0).get("id"));
    }

    @Test
    void deptScope_sqlReferencesRealTables_notPhantomUserU() {
        stubScope(DataScopeType.DEPT, null);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq(user), eq(tenant))).thenReturn(List.of("/east"));

        String sql = enforcer.resolveDataScopeSql(user, tenant, "created_by");
        assertNotNull(sql);
        // A-1 核心断言 1：不许再引用幽灵表 user_u
        assertFalse(sql.contains("user_u"),
                "DEPT 谓词不得引用不存在的表 user_u（修复前缺陷）: " + sql);
        // A-1 核心断言 2：必须是子树（path 前缀）而不是叶子部门相等
        assertTrue(sql.contains("org_unit") && sql.contains("path LIKE"),
                "DEPT 谓词必须走 org_unit.path 子树前缀匹配: " + sql);
    }

    @Test
    void deptScope_userWithoutDept_fallsBackToSelf() {
        stubScope(DataScopeType.DEPT, null);
        // 用户无部门 → path 查询返回空
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq(user), eq(tenant))).thenReturn(List.of());

        String sql = enforcer.resolveDataScopeSql(user, tenant, "created_by");
        assertEquals("created_by = '" + user + "'",
                sql, "无部门用户必须收敛为 SELF（D6），不得暗示更大范围");

        // 行级：只剩自己的记录
        UUID other = UUID.randomUUID();
        List<Map<String, Object>> records = List.of(
                Map.of("id", 1, "created_by", user.toString()),
                Map.of("id", 2, "created_by", other.toString()));
        var filtered = enforcer.filterRecordsByDataScope(user, tenant, "orders", records);
        assertEquals(1, filtered.size());
        assertEquals(1, filtered.get(0).get("id"));
    }

    @Test
    void deptScope_rowFilter_usesSubtreeMembers_notDepartmentIdPresence() {
        stubScope(DataScopeType.DEPT, null);
        UUID subtreePeer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        stubEastSubtree(subtreePeer);

        Map<String, Object> recNoOwner = new java.util.HashMap<>();
        recNoOwner.put("id", 4);
        recNoOwner.put("created_by", null);
        List<Map<String, Object>> records = List.of(
                Map.of("id", 1, "created_by", user.toString()),        // 本人
                Map.of("id", 2, "created_by", subtreePeer.toString()), // 同子树成员
                Map.of("id", 3, "created_by", outsider.toString()),   // 子树之外
                recNoOwner);                                            // 无归属
        var filtered = enforcer.filterRecordsByDataScope(user, tenant, "orders", records);
        // A-1 核心断言 3：子树成员可见、外人不可见 ——
        // 修复前判定是 record.department_id != null，业务表根本没有这一列
        assertEquals(Set.of(1, 2), Set.copyOf(filtered.stream()
                        .map(r -> (Integer) r.get("id")).toList()),
                "DEPT 行级过滤必须按「创建者 ∈ 子树成员」判定");
    }

    @Test
    void deptScope_dbFailure_convergesToSelf() {
        stubScope(DataScopeType.DEPT, null);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq(user), eq(tenant)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        var filtered = enforcer.filterRecordsByDataScope(user, tenant, "orders", List.of(
                Map.of("id", 1, "created_by", user.toString()),
                Map.of("id", 2, "created_by", UUID.randomUUID().toString())));
        assertEquals(1, filtered.size(), "DB 异常时收敛 SELF —— 不得整表放行");
    }

    @Test
    void customScope_parsesUserExpression() {
        stubScope(DataScopeType.CUSTOM,
                "{\"userExpression\":\"created_by = '${userId}'\"}");

        String sql = enforcer.resolveDataScopeSql(user, tenant, "created_by");
        assertNotNull(sql, "CUSTOM userExpression 必须生成谓词");
        assertTrue(sql.contains(user.toString()),
                "CUSTOM userExpression 的 ${userId} 占位符必须被替换为真实 userId: " + sql);
    }

    @Test
    @DisplayName("T1.3 下推：SELF → created_by = ?（参数化，不拼字符串）")
    void dataScopePlan_self_isParameterized() {
        stubScope(DataScopeType.SELF, null);

        var plan = enforcer.resolveDataScopePlan(user, tenant, "created_by");

        assertNotNull(plan.predicate());
        assertEquals("created_by = ?", plan.predicate().sql());
        assertEquals(List.of(user), plan.predicate().params());
        assertFalse(plan.customFallback());
    }

    @Test
    @DisplayName("T1.3 下推：DEPT → 子树子查询 + path 前缀参数")
    void dataScopePlan_dept_pushesSubtreePredicate() {
        stubScope(DataScopeType.DEPT, null);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq(user), eq(tenant))).thenReturn(List.of("/east/"));

        var plan = enforcer.resolveDataScopePlan(user, tenant, "created_by");

        assertNotNull(plan.predicate());
        assertTrue(plan.predicate().sql().contains("org_unit o"));
        assertTrue(plan.predicate().sql().contains("o.path LIKE ?"));
        assertFalse(plan.predicate().sql().contains("user_u"));
        assertEquals(List.of(tenant, "/east/%"), plan.predicate().params());
    }

    @Test
    @DisplayName("T1.3 CUSTOM 无法安全下推 → customFallback=true（保留内存判定）")
    void dataScopePlan_custom_fallsBackToMemory() {
        stubScope(DataScopeType.CUSTOM, "{\"userExpression\":\"created_by = '${userId}'\"}");

        var plan = enforcer.resolveDataScopePlan(user, tenant, "created_by");

        assertNull(plan.predicate());
        assertTrue(plan.customFallback());
    }

    private AclPolicyEntity newPolicy(UUID roleId, DataScopeType type, String scopeConfigJson) {
        AclPolicyEntity p = new AclPolicyEntity();
        p.setId(UUID.randomUUID());
        p.setRoleId(roleId);
        p.setType(AclPolicyEntity.Type.ACTION);
        p.setSubject("orders");
        p.setAction(AclPolicyEntity.Action.READ);
        p.setConfigJson("{}");
        p.setDataScopeType(type);
        p.setDataScopeConfigJson(scopeConfigJson);
        p.setTenantId(tenant);
        return p;
    }
}
