package com.nocobase.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RowAclService 单元测试(Week 20 P5 抬红线).
 *
 * <p>覆盖 evaluate 入口、filterReadable、evaluateExpression、resolveValue。
 */
class RowAclServiceTest {

    private AclRowPolicyRepository repo;
    private RowAclService service;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String TENANT = "tenant_default";
    private static final String COLLECTION = "customer";
    private static final UUID USER = UUID.randomUUID();
    private static final String ROLE = "manager";

    @BeforeEach
    void setUp() {
        repo = mock(AclRowPolicyRepository.class);
        service = new RowAclService(repo, mapper);
        when(repo.findApplicable(any(), any(), any())).thenReturn(List.of());
    }

    /* === evaluate 入口 === */

    @Test
    void evaluateRead_noPolicy_failClosed_denies() {
        // Stage 1 安全收口:无行级 policy 时 fail-closed(默认拒绝)。
        assertThat(service.evaluateRead(TENANT, COLLECTION, Map.of("name", "alice"),
                new RowAclService.Principal(USER.toString(), List.of(ROLE)))).isFalse();
    }

    @Test
    void evaluateRead_policyHits_returnsTrue() {
        var p = rowPolicy("user", USER.toString(), "read",
                Map.of("field", "dept", "op", "eq", "value", "sales"));
        when(repo.findApplicable(TENANT, COLLECTION, "read")).thenReturn(List.of(p));
        assertThat(service.evaluateRead(TENANT, COLLECTION,
                Map.of("dept", "sales"),
                new RowAclService.Principal(USER.toString(), List.of(ROLE)))).isTrue();
    }

    @Test
    void evaluateRead_policyMisses_returnsFalse() {
        var p = rowPolicy("user", USER.toString(), "read",
                Map.of("field", "dept", "op", "eq", "value", "sales"));
        when(repo.findApplicable(TENANT, COLLECTION, "read")).thenReturn(List.of(p));
        assertThat(service.evaluateRead(TENANT, COLLECTION,
                Map.of("dept", "engineering"),
                new RowAclService.Principal(USER.toString(), List.of(ROLE)))).isFalse();
    }

    @Test
    void evaluateUpdate_delegatesToUpdatePolicies() {
        var p = rowPolicy("user", USER.toString(), "update",
                Map.of("field", "dept", "op", "eq", "value", "sales"));
        when(repo.findApplicable(TENANT, COLLECTION, "update")).thenReturn(List.of(p));
        when(repo.findApplicable(TENANT, COLLECTION, "read")).thenReturn(List.of());
        assertThat(service.evaluateUpdate(TENANT, COLLECTION,
                Map.of("dept", "sales"),
                new RowAclService.Principal(USER.toString(), List.of(ROLE)))).isTrue();
    }

    @Test
    void evaluateUpdate_fallbackToReadWhenNoUpdatePolicy() {
        var p = rowPolicy("user", USER.toString(), "read",
                Map.of("field", "dept", "op", "eq", "value", "sales"));
        when(repo.findApplicable(TENANT, COLLECTION, "read")).thenReturn(List.of(p));
        when(repo.findApplicable(TENANT, COLLECTION, "update")).thenReturn(List.of());
        assertThat(service.evaluateUpdate(TENANT, COLLECTION,
                Map.of("dept", "sales"),
                new RowAclService.Principal(USER.toString(), List.of(ROLE)))).isTrue();
    }

    @Test
    void evaluateDelete_usesDeletePolicy() {
        var p = rowPolicy("user", USER.toString(), "delete",
                Map.of("field", "dept", "op", "eq", "value", "sales"));
        when(repo.findApplicable(TENANT, COLLECTION, "delete")).thenReturn(List.of(p));
        assertThat(service.evaluateDelete(TENANT, COLLECTION,
                Map.of("dept", "sales"),
                new RowAclService.Principal(USER.toString(), List.of(ROLE)))).isTrue();
    }

    /* === filterReadable === */

    @Test
    void filterReadable_noPolicy_returnsAll() {
        List<Map<String, Object>> records = List.of(
                Map.of("id", "1", "name", "alice"),
                Map.of("id", "2", "name", "bob"));
        assertThat(service.filterReadable(TENANT, COLLECTION, records,
                new RowAclService.Principal(USER.toString(), List.of(ROLE)))).hasSize(2);
    }

    @Test
    void filterReadable_filtersByPolicy() {
        var p = rowPolicy("user", USER.toString(), "read",
                Map.of("field", "dept", "op", "eq", "value", "sales"));
        when(repo.findApplicable(TENANT, COLLECTION, "read")).thenReturn(List.of(p));
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(Map.of("id", "1", "dept", "sales"));
        records.add(Map.of("id", "2", "dept", "engineering"));
        records.add(Map.of("id", "3", "dept", "sales"));
        var out = service.filterReadable(TENANT, COLLECTION, records,
                new RowAclService.Principal(USER.toString(), List.of(ROLE)));
        assertThat(out).hasSize(2);
        assertThat(out).extracting(r -> r.get("id")).containsExactly("1", "3");
    }

    /* === evaluateExpression op 覆盖 === */

    @Test
    void op_eq_neq() throws Exception {
        assertThat(invokeEval(Map.of("field", "x", "op", "eq", "value", "1"),
                Map.of("x", "1"))).isTrue();
        assertThat(invokeEval(Map.of("field", "x", "op", "neq", "value", "1"),
                Map.of("x", "2"))).isTrue();
    }

    @Test
    void op_in() throws Exception {
        assertThat(invokeEval(Map.of("field", "x", "op", "in", "value", List.of("a", "b", "c")),
                Map.of("x", "b"))).isTrue();
        assertThat(invokeEval(Map.of("field", "x", "op", "in", "value", List.of("a", "b", "c")),
                Map.of("x", "z"))).isFalse();
        // null actual → false
        assertThat(invokeEval(Map.of("field", "x", "op", "in", "value", List.of("a")),
                new HashMap<>())).isFalse();
    }

    @Test
    void op_isNull_notNull() throws Exception {
        // is_null: value 不能传 null(Map.of 拒绝),但 expression 内 value=null JSON 是合法的
        // 用 HashMap 绕开
        Map<String, Object> isNullExpr = new HashMap<>();
        isNullExpr.put("field", "x");
        isNullExpr.put("op", "is_null");
        isNullExpr.put("value", null);
        assertThat(invokeEval(isNullExpr, new HashMap<>())).isTrue();
        // not_null + 有值 → true
        Map<String, Object> notNullExpr = new HashMap<>();
        notNullExpr.put("field", "x");
        notNullExpr.put("op", "not_null");
        notNullExpr.put("value", null);
        assertThat(invokeEval(notNullExpr, Map.of("x", "x"))).isTrue();
    }

    @Test
    void op_contains() throws Exception {
        assertThat(invokeEval(Map.of("field", "name", "op", "contains", "value", "al"),
                Map.of("name", "alice"))).isTrue();
        assertThat(invokeEval(Map.of("field", "name", "op", "contains", "value", "zz"),
                Map.of("name", "alice"))).isFalse();
        assertThat(invokeEval(Map.of("field", "name", "op", "contains", "value", "x"),
                new HashMap<>())).isFalse();
    }

    @Test
    void op_unknown_returnsFalse() throws Exception {
        assertThat(invokeEval(Map.of("field", "x", "op", "between", "value", "1"),
                Map.of("x", "1"))).isFalse();
    }

    /* === appliesTo: user vs role principal === */

    @Test
    void appliesTo_rolePrincipal_miss() {
        var p = rowPolicy("role", "other_role", "read",
                Map.of("field", "x", "op", "eq", "value", "1"));
        when(repo.findApplicable(TENANT, COLLECTION, "read")).thenReturn(List.of(p));
        assertThat(service.evaluateRead(TENANT, COLLECTION,
                Map.of("x", "999"),
                new RowAclService.Principal(USER.toString(), List.of(ROLE)))).isTrue();
    }

    /* === $currentUser / $currentRoles 占位符 === */

    @Test
    void resolveValue_currentUser() throws Exception {
        assertThat(invokeEval(Map.of("field", "owner", "op", "eq", "value", "$currentUser"),
                Map.of("owner", USER.toString()))).isTrue();
    }

    @Test
    void resolveValue_currentRoles() throws Exception {
        assertThat(invokeEval(Map.of("field", "role", "op", "in", "value", "$currentRoles"),
                Map.of("role", ROLE))).isTrue();
        assertThat(invokeEval(Map.of("field", "role", "op", "in", "value", "$currentRoles"),
                Map.of("role", "other_role"))).isFalse();
    }

    @Test
    void resolveValue_plainString() throws Exception {
        assertThat(invokeEval(Map.of("field", "dept", "op", "eq", "value", "sales"),
                Map.of("dept", "sales"))).isTrue();
    }

    /* === exception path: 非法 JSON log warn 后跳过 === */

    @Test
    void evaluateRead_invalidJson_logsAndSkips() {
        // 唯一 policy 但 expression 非法 JSON → evaluateExpression 抛异常被 catch
        // matchesAny 继续到 next 适用策略(没有), → 返回 false
        // (与"无 policy 放行"不同 — 这里是"有 policy 但全部失败",应拒绝,符合 fail-safe)
        AclRowPolicyEntity bad = rowPolicy("user", USER.toString(), "read", null);
        bad.setExpression("{not valid json");
        when(repo.findApplicable(TENANT, COLLECTION, "read")).thenReturn(List.of(bad));
        assertThat(service.evaluateRead(TENANT, COLLECTION, Map.of("x", "1"),
                new RowAclService.Principal(USER.toString(), List.of(ROLE)))).isFalse();
    }

    /* === helpers === */

    private AclRowPolicyEntity rowPolicy(String principalType, String principalId,
                                          String action, Map<String, Object> expr) {
        try {
            AclRowPolicyEntity p = new AclRowPolicyEntity();
            p.setId(UUID.randomUUID());
            p.setTenantId(TENANT);
            p.setCollection(COLLECTION);
            p.setPrincipalType(principalType);
            p.setPrincipalId(principalId);
            p.setAction(action);
            p.setExpression(mapper.writeValueAsString(expr == null ? Map.of() : expr));
            p.setPriority(0);
            p.setEnabled(true);
            return p;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean invokeEval(Map<String, Object> expr, Map<String, Object> record) throws Exception {
        AclRowPolicyEntity p = rowPolicy("user", USER.toString(), "read", expr);
        when(repo.findApplicable(TENANT, COLLECTION, "read")).thenReturn(List.of(p));
        return service.evaluateRead(TENANT, COLLECTION, record,
                new RowAclService.Principal(USER.toString(), List.of(ROLE)));
    }
}