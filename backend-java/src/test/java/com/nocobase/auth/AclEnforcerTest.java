package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * AclEnforcer 单元测试(Week 18 P5 阶段 5).
 *
 * <p>覆盖:collection-level / row-level / field-level(read + write) 的所有分支,
 * 包括 NULL 处理语义与角色继承 CTE 容错。
 */
class AclEnforcerTest {

    private UserRoleRepository userRoleRepo;
    private AclPolicyRepository policyRepo;
    private RoleRepository roleRepo;
    private AclEnforcer enforcer;       // fail-closed (default)
    private AclEnforcer enforcerOpen;   // fail-open (灰度回滚窗口)

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ROLE = UUID.randomUUID();
    private static final UUID ANCESTOR = UUID.randomUUID();
    private static final String TENANT = "tenant_default";
    private static final String COLLECTION = "customer";

    @BeforeEach
    void setUp() {
        userRoleRepo = mock(UserRoleRepository.class);
        policyRepo = mock(AclPolicyRepository.class);
        roleRepo = mock(RoleRepository.class);
        enforcer = new AclEnforcer(userRoleRepo, policyRepo, roleRepo, false);
        enforcerOpen = new AclEnforcer(userRoleRepo, policyRepo, roleRepo, true);

        var ur = mock(UserRoleEntity.class);
        var urId = mock(UserRoleEntity.UserRoleId.class);
        when(urId.getRoleId()).thenReturn(ROLE);
        when(ur.getId()).thenReturn(urId);
        when(userRoleRepo.findByIdUserId(USER)).thenReturn(List.of(ur));
        when(roleRepo.findSelfAndAncestors(any(UUID.class), any(String.class)))
                .thenAnswer(inv -> List.of(ROLE));
    }

    private static AclPolicyEntity actionPolicy(AclPolicyEntity.Action action, String config) {
        AclPolicyEntity p = new AclPolicyEntity();
        p.setId(UUID.randomUUID()); p.setRoleId(ROLE);
        p.setType(AclPolicyEntity.Type.ACTION); p.setAction(action);
        p.setSubject(COLLECTION); p.setConfigJson(config); p.setTenantId(TENANT);
        return p;
    }

    private static AclPolicyEntity fieldPolicy(AclPolicyEntity.Action action, String config) {
        AclPolicyEntity p = new AclPolicyEntity();
        p.setId(UUID.randomUUID()); p.setRoleId(ROLE);
        p.setType(AclPolicyEntity.Type.FIELD); p.setAction(action);
        p.setSubject(COLLECTION); p.setConfigJson(config); p.setTenantId(TENANT);
        return p;
    }

    private static AclPolicyEntity rowPolicy(String config) {
        AclPolicyEntity p = new AclPolicyEntity();
        p.setId(UUID.randomUUID()); p.setRoleId(ROLE);
        p.setType(AclPolicyEntity.Type.ROW);
        p.setSubject(COLLECTION); p.setConfigJson(config); p.setTenantId(TENANT);
        return p;
    }

    /* ============================================================ */
    /*  isAllowed — collection-level ACTION                          */
    /* ============================================================ */

    @Test
    void isAllowed_noPolicy_failClosed_returnsFalse() {
        when(policyRepo.findByRoleIdAndTenantId(any(), any())).thenReturn(List.of());
        assertThat(enforcer.isAllowed(USER, TENANT, COLLECTION, AclPolicyEntity.Action.READ))
                .isFalse();
    }

    @Test
    void isAllowed_noPolicy_failOpen_returnsTrue() {
        when(policyRepo.findByRoleIdAndTenantId(any(), any())).thenReturn(List.of());
        assertThat(enforcerOpen.isAllowed(USER, TENANT, COLLECTION, AclPolicyEntity.Action.READ))
                .isTrue();
    }

    @Test
    void isAllowed_actionReadHit_returnsTrue() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT))
                .thenReturn(List.of(actionPolicy(AclPolicyEntity.Action.READ, "{}")));
        assertThat(enforcer.isAllowed(USER, TENANT, COLLECTION, AclPolicyEntity.Action.READ))
                .isTrue();
    }

    @Test
    void isAllowed_createOnly_deniesRead() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT))
                .thenReturn(List.of(actionPolicy(AclPolicyEntity.Action.CREATE, "{}")));
        assertThat(enforcer.isAllowed(USER, TENANT, COLLECTION, AclPolicyEntity.Action.READ))
                .isFalse();
    }

    @Test
    void isAllowed_fieldOrRowOnly_allowsCrud() {
        // fail-open 语义:仅 FIELD/ROW 策略时 crud 仍放行(灰度回滚窗口)。
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.READ, "{\"hidden\":[\"x\"]}"),
                rowPolicy("{\"filters\":[]}")));
        assertThat(enforcerOpen.isAllowed(USER, TENANT, COLLECTION, AclPolicyEntity.Action.UPDATE))
                .isTrue();
    }

    @Test
    void isAllowed_fieldOrRowOnly_failClosed_deniesCrud() {
        // fail-closed(默认):仅 FIELD/ROW 策略,无 ACTION(UPDATE)策略 → 拒绝。
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.READ, "{\"hidden\":[\"x\"]}"),
                rowPolicy("{\"filters\":[]}")));
        assertThat(enforcer.isAllowed(USER, TENANT, COLLECTION, AclPolicyEntity.Action.UPDATE))
                .isFalse();
    }

    @Test
    void isAllowed_noRole_returnsFalse() {
        when(userRoleRepo.findByIdUserId(USER)).thenReturn(List.of());
        assertThat(enforcer.isAllowed(USER, TENANT, COLLECTION, AclPolicyEntity.Action.READ))
                .isFalse();
    }

    @Test
    void isAllowed_wrongSubject_failClosed_returnsFalse() {
        AclPolicyEntity p = new AclPolicyEntity();
        p.setId(UUID.randomUUID()); p.setRoleId(ROLE);
        p.setType(AclPolicyEntity.Type.ACTION); p.setAction(AclPolicyEntity.Action.READ);
        p.setSubject("other"); p.setConfigJson("{}"); p.setTenantId(TENANT);
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(p));
        assertThat(enforcer.isAllowed(USER, TENANT, COLLECTION, AclPolicyEntity.Action.READ))
                .isFalse();
    }

    @Test
    void isAllowed_wrongSubject_failOpen_returnsTrue() {
        AclPolicyEntity p = new AclPolicyEntity();
        p.setId(UUID.randomUUID()); p.setRoleId(ROLE);
        p.setType(AclPolicyEntity.Type.ACTION); p.setAction(AclPolicyEntity.Action.READ);
        p.setSubject("other"); p.setConfigJson("{}"); p.setTenantId(TENANT);
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(p));
        assertThat(enforcerOpen.isAllowed(USER, TENANT, COLLECTION, AclPolicyEntity.Action.READ))
                .isTrue();
    }

    @Test
    void assertCan_throws403OnDeny() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT))
                .thenReturn(List.of(actionPolicy(AclPolicyEntity.Action.CREATE, "{}")));
        assertThatThrownBy(() -> enforcer.assertCan(USER, TENANT, COLLECTION,
                AclPolicyEntity.Action.READ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /* === filterReadableFields === */

    @Test
    void filterReadableFields_noFieldPolicy_returnsNull() {
        when(policyRepo.findByRoleIdAndTenantId(any(), any())).thenReturn(List.of());
        assertThat(enforcer.filterReadableFields(USER, TENANT, COLLECTION)).isNull();
    }

    @Test
    void filterReadableFields_fieldRead_returnsHidden() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.READ, "{\"hidden\":[\"ssn\",\"salary\"]}")));
        assertThat(enforcer.filterReadableFields(USER, TENANT, COLLECTION))
                .containsExactlyInAnyOrder("ssn", "salary");
    }

    @Test
    void filterReadableFields_fieldUpdate_ignored() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.UPDATE, "{\"hidden\":[\"salary\"]}")));
        assertThat(enforcer.filterReadableFields(USER, TENANT, COLLECTION)).isNull();
    }

    @Test
    void filterReadableFields_multipleReads_union() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.READ, "{\"hidden\":[\"ssn\"]}"),
                fieldPolicy(AclPolicyEntity.Action.READ, "{\"hidden\":[\"salary\",\"phone\"]}")));
        assertThat(enforcer.filterReadableFields(USER, TENANT, COLLECTION))
                .containsExactlyInAnyOrder("ssn", "salary", "phone");
    }

    /* === filterWritableFields === */

    @Test
    void filterWritableFields_noPolicy_returnsNull() {
        when(policyRepo.findByRoleIdAndTenantId(any(), any())).thenReturn(List.of());
        assertThat(enforcer.filterWritableFields(USER, TENANT, COLLECTION)).isNull();
    }

    @Test
    void filterWritableFields_actionNull_picksCreateAndUpdate() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.UPDATE, "{\"hidden\":[\"salary\"]}")));
        assertThat(enforcer.filterWritableFields(USER, TENANT, COLLECTION))
                .containsExactly("salary");
    }

    @Test
    void filterWritableFields_actionCreate_ignoresUpdateOnly() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.UPDATE, "{\"hidden\":[\"salary\"]}")));
        assertThat(enforcer.filterWritableFields(USER, TENANT, COLLECTION,
                AclPolicyEntity.Action.CREATE)).isNull();
    }

    @Test
    void filterWritableFields_actionUpdate_hitsUpdate() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.UPDATE, "{\"hidden\":[\"salary\"]}")));
        assertThat(enforcer.filterWritableFields(USER, TENANT, COLLECTION,
                AclPolicyEntity.Action.UPDATE)).containsExactly("salary");
    }

    @Test
    void filterWritableFields_separateCreateAndUpdate() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.CREATE, "{\"hidden\":[\"name\"]}"),
                fieldPolicy(AclPolicyEntity.Action.UPDATE, "{\"hidden\":[\"salary\"]}")));
        assertThat(enforcer.filterWritableFields(USER, TENANT, COLLECTION,
                AclPolicyEntity.Action.CREATE)).containsExactly("name");
        assertThat(enforcer.filterWritableFields(USER, TENANT, COLLECTION,
                AclPolicyEntity.Action.UPDATE)).containsExactly("salary");
        assertThat(enforcer.filterWritableFields(USER, TENANT, COLLECTION))
                .containsExactlyInAnyOrder("name", "salary");
    }

    @Test
    void filterWritableFields_fieldRead_doesNotAffectWrite() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.READ, "{\"hidden\":[\"salary\"]}")));
        assertThat(enforcer.filterWritableFields(USER, TENANT, COLLECTION,
                AclPolicyEntity.Action.UPDATE)).isNull();
    }

    /* === assertCanWriteFields === */

    @Test
    void assertCanWriteFields_emptyData_passes() {
        enforcer.assertCanWriteFields(USER, TENANT, COLLECTION, new HashMap<>());
    }

    @Test
    void assertCanWriteFields_nullData_passes() {
        enforcer.assertCanWriteFields(USER, TENANT, COLLECTION, null);
    }

    @Test
    void assertCanWriteFields_noPolicy_passes() {
        when(policyRepo.findByRoleIdAndTenantId(any(), any())).thenReturn(List.of());
        Map<String, Object> data = new HashMap<>();
        data.put("salary", 99999);
        enforcer.assertCanWriteFields(USER, TENANT, COLLECTION, data,
                AclPolicyEntity.Action.UPDATE);
    }

    @Test
    void assertCanWriteFields_noFieldPolicy_failClosed_allowsAll() {
        when(policyRepo.findByRoleIdAndTenantId(any(), any())).thenReturn(List.of());
        Map<String, Object> data = new HashMap<>();
        data.put("salary", 99999);
        enforcer.assertCanWriteFields(USER, TENANT, COLLECTION, data,
                AclPolicyEntity.Action.UPDATE);
    }

    @Test
    void assertCanWriteFields_forbidden_throws403() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.UPDATE, "{\"hidden\":[\"salary\"]}")));
        Map<String, Object> data = new HashMap<>();
        data.put("name", "ok");
        data.put("salary", 99999);
        assertThatThrownBy(() -> enforcer.assertCanWriteFields(USER, TENANT, COLLECTION,
                data, AclPolicyEntity.Action.UPDATE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void assertCanWriteFields_nullValue_alsoForbidden() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.UPDATE, "{\"hidden\":[\"salary\"]}")));
        Map<String, Object> data = new HashMap<>();
        data.put("salary", null);
        assertThatThrownBy(() -> enforcer.assertCanWriteFields(USER, TENANT, COLLECTION,
                data, AclPolicyEntity.Action.UPDATE))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void assertCanWriteFields_createAction_bypassesUpdateOnlyField() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.UPDATE, "{\"hidden\":[\"salary\"]}")));
        Map<String, Object> data = new HashMap<>();
        data.put("salary", 50000);
        enforcer.assertCanWriteFields(USER, TENANT, COLLECTION, data,
                AclPolicyEntity.Action.CREATE);
    }

    /* === loadRoleIdsIncludingInheritance === */

    @Test
    void loadRoles_noRoles_returnsEmpty() {
        when(userRoleRepo.findByIdUserId(USER)).thenReturn(List.of());
        assertThat(enforcer.loadRoleIdsIncludingInheritance(USER, TENANT)).isEmpty();
    }

    @Test
    void loadRoles_directOnly() {
        when(roleRepo.findSelfAndAncestors(ROLE, TENANT)).thenReturn(List.of(ROLE));
        assertThat(enforcer.loadRoleIdsIncludingInheritance(USER, TENANT))
                .containsExactly(ROLE);
    }

    @Test
    void loadRoles_ancestorChain() {
        when(roleRepo.findSelfAndAncestors(ROLE, TENANT)).thenReturn(List.of(ROLE, ANCESTOR));
        assertThat(enforcer.loadRoleIdsIncludingInheritance(USER, TENANT))
                .containsExactly(ROLE, ANCESTOR);
    }

    @Test
    void loadRoles_cteFailure_tolerated() {
        when(roleRepo.findSelfAndAncestors(any(), any()))
                .thenThrow(new RuntimeException("CTE syntax error"));
        assertThat(enforcer.loadRoleIdsIncludingInheritance(USER, TENANT))
                .containsExactly(ROLE);
    }

    @Test
    void loadRoles_dedupAncestors() {
        UUID anotherRole = UUID.randomUUID();
        var ur1 = mock(UserRoleEntity.class);
        var id1 = mock(UserRoleEntity.UserRoleId.class);
        when(id1.getRoleId()).thenReturn(ROLE);
        when(ur1.getId()).thenReturn(id1);
        var ur2 = mock(UserRoleEntity.class);
        var id2 = mock(UserRoleEntity.UserRoleId.class);
        when(id2.getRoleId()).thenReturn(anotherRole);
        when(ur2.getId()).thenReturn(id2);
        when(userRoleRepo.findByIdUserId(USER)).thenReturn(List.of(ur1, ur2));
        when(roleRepo.findSelfAndAncestors(ROLE, TENANT)).thenReturn(List.of(ROLE, ANCESTOR));
        when(roleRepo.findSelfAndAncestors(anotherRole, TENANT))
                .thenReturn(List.of(anotherRole, ANCESTOR));
        var ids = enforcer.loadRoleIdsIncludingInheritance(USER, TENANT);
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).contains(ROLE, anotherRole, ANCESTOR);
    }

    /* === filterRecord === */

    @Test
    void filterRecord_null_returnsNull() {
        assertThat(enforcer.filterRecord(USER, TENANT, COLLECTION, null)).isNull();
    }

    @Test
    void filterRecord_noFieldPolicy_passesThrough() {
        when(policyRepo.findByRoleIdAndTenantId(any(), any())).thenReturn(List.of());
        Map<String, Object> rec = new HashMap<>();
        rec.put("name", "alice");
        rec.put("salary", 5000);
        Map<String, Object> out = enforcer.filterRecord(USER, TENANT, COLLECTION, rec);
        assertThat(out).containsEntry("name", "alice").containsEntry("salary", 5000);
    }

    @Test
    void filterRecord_hiddenRemoved() {
        when(policyRepo.findByRoleIdAndTenantId(ROLE, TENANT)).thenReturn(List.of(
                fieldPolicy(AclPolicyEntity.Action.READ, "{\"hidden\":[\"salary\"]}")));
        Map<String, Object> rec = new HashMap<>();
        rec.put("name", "alice");
        rec.put("salary", 5000);
        Map<String, Object> out = enforcer.filterRecord(USER, TENANT, COLLECTION, rec);
        assertThat(out).containsOnlyKeys("name");
        assertThat(out).doesNotContainKey("salary");
    }
}