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
 * <p>R8 收口后 DEPT SQL 只剩**一条**实现路径：{@link AclEnforcer#resolveDataScopePlan}
 * （参数化 ? 占位）。测试直接断言该计划，不再比对字符串拼接版本。
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
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq(tenant), eq("/east")))
                .thenReturn(List.of(aliceId));
    }

    @Test
    void noScopePolicy_returnsAllPlan() {
        stubScope(null, null);

        var plan = enforcer.resolveDataScopePlan(user, tenant, "created_by");
        assertNull(plan.predicate());
        // ScopePlan 同样应为 ALL（批量端点使用）
        assertTrue(enforcer.resolveScopePlan(user, tenant).isAll());
        List<Map<String, Object>> records = List.of(Map.of("id", 1));
        assertEquals(1, enforcer.filterRecordsByDataScope(user, tenant, "orders", records).size());
    }

    @Test
    void selfScope_createsParameterizedPredicate() {
        stubScope(DataScopeType.SELF, null);

        var scopePlan = enforcer.resolveScopePlan(user, tenant);
        assertEquals(AclEnforcer.ScopeKind.SELF, scopePlan.kind());
        assertEquals("created_by = ?", scopePlan.predicate().sql());
        assertEquals(List.of(user), scopePlan.predicate().params());

        List<Map<String, Object>> records = List.of(
                Map.of("id", 1, "created_by", user.toString()),
                Map.of("id", 2, "created_by", UUID.randomUUID().toString()));
        List<Map<String, Object>> filtered = enforcer.filterInScope(scopePlan, records);
        assertEquals(1, filtered.size());
        assertEquals(1, filtered.get(0).get("id"));
    }

    @Test
    @DisplayName("DEPT 谓词引用真实表 users/org_unit，子树用 path LIKE ?")
    void deptScope_planReferencesRealTables_notPhantomUserU() {
        stubScope(DataScopeType.DEPT, null);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq(user), eq(tenant))).thenReturn(List.of("/east/"));

        var plan = enforcer.resolveDataScopePlan(user, tenant, "created_by");
        assertNotNull(plan.predicate());
        String sql = plan.predicate().sql();
        assertFalse(sql.contains("user_u"), "DEPT 谓词不得引用不存在的表 user_u: " + sql);
        assertTrue(sql.contains("org_unit") && sql.contains("o.path LIKE ?"), sql);
        assertEquals(List.of(tenant, "/east/%"), plan.predicate().params());
    }

    @Test
    void deptScope_userWithoutDept_fallsBackToSelf() {
        stubScope(DataScopeType.DEPT, null);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq(user), eq(tenant))).thenReturn(List.of());

        var plan = enforcer.resolveDataScopePlan(user, tenant, "created_by");
        assertEquals("created_by = ?", plan.predicate().sql());
        assertEquals(List.of(user), plan.predicate().params());

        UUID other = UUID.randomUUID();
        var filtered = enforcer.filterRecordsByDataScope(user, tenant, "orders", List.of(
                Map.of("id", 1, "created_by", user.toString()),
                Map.of("id", 2, "created_by", other.toString())));
        assertEquals(1, filtered.size());
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
                Map.of("id", 1, "created_by", user.toString()),
                Map.of("id", 2, "created_by", subtreePeer.toString()),
                Map.of("id", 3, "created_by", outsider.toString()),
                recNoOwner);
        var filtered = enforcer.filterRecordsByDataScope(user, tenant, "orders", records);
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
    @DisplayName("R8 CUSTOM 无实现：不下推 SQL，内存判定要求 _data_scope_match")
    void customScope_isUnsupportedFallback() {
        stubScope(DataScopeType.CUSTOM,
                "{\"userExpression\":\"created_by = '${userId}'\"}");

        var plan = enforcer.resolveDataScopePlan(user, tenant, "created_by");
        assertNull(plan.predicate(), "CUSTOM 不允许拼 SQL 文本");
        assertTrue(plan.customFallback());

        var filtered = enforcer.filterRecordsByDataScope(user, tenant, "orders", List.of(
                Map.of("id", 1),
                Map.of("id", 2, "_data_scope_match", true)));
        assertEquals(1, filtered.size());
        assertEquals(2, filtered.get(0).get("id"));
    }

    @Test
    @DisplayName("R1c：resolveScopePlan 一次解析，批量 filterInScope 不再查库")
    void scopePlan_filtersBatchWithSingleResolution() {
        stubScope(DataScopeType.SELF, null);

        var plan = enforcer.resolveScopePlan(user, tenant);
        List<Map<String, Object>> records = List.of(
                Map.of("id", 1, "created_by", user.toString()),
                Map.of("id", 2, "created_by", UUID.randomUUID().toString()),
                Map.of("id", 3, "created_by", user.toString()));
        var filtered = enforcer.filterInScope(plan, records);
        assertEquals(2, filtered.size());

        // 只解析了一次角色（filterInScope 内部不再查库）
        verify(userRoleRepo, times(1)).findByIdUserId(user);
    }

    @Test
    @DisplayName("R1c：越界记录 → 404，不泄露存在性")
    void assertRecordInScope_throws404() {
        stubScope(DataScopeType.SELF, null);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> enforcer.assertRecordInScope(user, tenant, "orders",
                        Map.of("id", 2, "created_by", UUID.randomUUID().toString())));
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
