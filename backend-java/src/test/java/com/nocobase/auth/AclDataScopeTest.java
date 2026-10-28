package com.nocobase.auth;

import com.nocobase.auth.AclPolicyEntity.DataScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * B7 返工验证：data_scope 字段从「全仓无调用的死代码」到「真实行级过滤」。
 * 覆盖 resolveDataScopeSql + filterRecordsByDataScope 的 SELF/DEPT/CUSTOM 分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AclDataScopeTest {

    @Mock private UserRoleRepository userRoleRepo;
    @Mock private AclPolicyRepository policyRepo;
    @Mock private RoleRepository roleRepo;

    private AclEnforcer enforcer;
    private final UUID user = UUID.randomUUID();
    private final String tenant = "tenant_a";

    @BeforeEach
    void setUp() {
        enforcer = new AclEnforcer(userRoleRepo, policyRepo, roleRepo, false);
    }

    private UserRoleEntity userRole(UUID roleId) {
        UserRoleEntity ur = new UserRoleEntity();
        ur.setId(new UserRoleEntity.UserRoleId());
        ur.getId().setUserId(user);
        ur.getId().setRoleId(roleId);
        return ur;
    }

    @Test
    void noScopePolicy_returnsNull_predicate() {
        UUID roleId = UUID.randomUUID();
        when(userRoleRepo.findByIdUserId(user)).thenReturn(List.of(userRole(roleId)));
        when(policyRepo.findByRoleIdAndTenantId(roleId, tenant)).thenReturn(List.of());

        assertNull(enforcer.resolveDataScopeSql(user, tenant, "created_by"));
    }

    @Test
    void selfScope_createsCreatedByPredicate() {
        UUID roleId = UUID.randomUUID();
        AclPolicyEntity policy = newPolicy(roleId, DataScopeType.SELF, null);
        when(userRoleRepo.findByIdUserId(user)).thenReturn(List.of(userRole(roleId)));
        when(policyRepo.findByRoleIdAndTenantId(roleId, tenant)).thenReturn(List.of(policy));
        // 继承查询
        when(roleRepo.findSelfAndAncestors(any(), any())).thenReturn(List.of(roleId));

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
    void deptScope_createsDepartmentPredicate() {
        UUID roleId = UUID.randomUUID();
        AclPolicyEntity policy = newPolicy(roleId, DataScopeType.DEPT, null);
        when(userRoleRepo.findByIdUserId(user)).thenReturn(List.of(userRole(roleId)));
        when(policyRepo.findByRoleIdAndTenantId(roleId, tenant)).thenReturn(List.of(policy));
        when(roleRepo.findSelfAndAncestors(any(), any())).thenReturn(List.of(roleId));

        String sql = enforcer.resolveDataScopeSql(user, tenant, "created_by");
        assertNotNull(sql);
        assertTrue(sql.toLowerCase().contains("department_id"),
                "DEPT 范围必须生成部门匹配谓词，修复前是死代码: " + sql);
    }

    @Test
    void customScope_parsesUserExpression() {
        UUID roleId = UUID.randomUUID();
        AclPolicyEntity policy = newPolicy(roleId, DataScopeType.CUSTOM,
                "{\"userExpression\":\"created_by = '${userId}'\"}");
        when(userRoleRepo.findByIdUserId(user)).thenReturn(List.of(userRole(roleId)));
        when(policyRepo.findByRoleIdAndTenantId(roleId, tenant)).thenReturn(List.of(policy));
        when(roleRepo.findSelfAndAncestors(any(), any())).thenReturn(List.of(roleId));

        String sql = enforcer.resolveDataScopeSql(user, tenant, "created_by");
        assertNotNull(sql, "CUSTOM userExpression 必须生成谓词");
        assertTrue(sql.contains(user.toString()),
                "CUSTOM userExpression 的 ${userId} 占位符必须被替换为真实 userId: " + sql);
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
