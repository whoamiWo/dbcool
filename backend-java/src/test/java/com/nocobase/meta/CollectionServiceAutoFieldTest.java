package com.nocobase.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * PHASE71：自动字段（createdTime / createdBy / lastModifiedTime / lastModifiedBy）行为锁定。
 *
 * <p>修复前的问题（审计发现）：
 * <ul>
 *   <li>{@code createdBy} / {@code lastModifiedBy} 硬编码为字符串 {@code "system"} ——
 *       审计追踪完全失效，看不出是谁创建/修改的；</li>
 *   <li>{@code lastModifiedTime} / {@code lastModifiedBy} 的填充带 {@code && blank} 条件 ——
 *       更新时旧值非空就**永不刷新**，语义退化成"创建时间"。</li>
 * </ul>
 *
 * <p>端到端已实测（容器内）：createdTime / createdBy / lastModifiedBy 均正确，
 * autonumber 序列递增。lastModifiedTime 的刷新因测试集合 ROW ACL 403 无法在
 * 端到端路径上验证，故在此用单测锁定。
 */
class CollectionServiceAutoFieldTest {

    private static final String FIELDS = """
            [
              {"name":"title","type":"text","required":false,"label":"标题"},
              {"name":"ct","type":"createdTime","required":false,"label":"创建时间"},
              {"name":"cb","type":"createdBy","required":false,"label":"创建人"},
              {"name":"lmt","type":"lastModifiedTime","required":false,"label":"修改时间"},
              {"name":"lmb","type":"lastModifiedBy","required":false,"label":"修改人"}
            ]
            """;

    private final CollectionService service =
            new CollectionService(null, null, null, new ObjectMapper());

    private CollectionMetaEntity meta() {
        CollectionMetaEntity m = new CollectionMetaEntity();
        m.setName("c");
        m.setTenantId("tenant_default");
        m.setFieldsJson(FIELDS);
        return m;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void insert_fillsCreatedAndModifiedFields() {
        Map<String, Object> out = service.applyFieldConstraints(meta(), Map.of("title", "v1"), null, true);
        assertEquals("v1", out.get("title"));
        assertEquals("system", out.get("cb"), "无 SecurityContext 时回退 system（非 web 上下文）");
        assertEquals("system", out.get("lmb"));
        // 创建时间与修改时间在插入时一致
        assertEquals(out.get("ct"), out.get("lmt"));
    }

    @Test
    void update_refreshesLastModifiedEvenWhenOldValueExists() {
        // 模拟更新：merged 里带着创建时写入的旧值
        Map<String, Object> merged = new HashMap<>();
        merged.put("title", "v2");
        merged.put("ct", "2020-01-01T00:00:00Z");
        merged.put("cb", "user-1");
        merged.put("lmt", "2020-01-01T00:00:00Z");   // 旧值非空 —— 修复前这里不会被覆盖
        merged.put("lmb", "user-1");

        // 用户只提交了 title —— 其余都是 merged 带过来的旧值，不应被判为"手工写入"
        Map<String, Object> out = service.applyFieldConstraints(
                meta(), merged, "some-id", false, java.util.Set.of("title"));

        // 创建的两个字段必须原样保留
        assertEquals("2020-01-01T00:00:00Z", out.get("ct"), "createdTime 不应在更新时被改动");
        assertEquals("user-1", out.get("cb"), "createdBy 不应在更新时被改动");

        // 修改的两个字段必须刷新（修复点）
        assertNotEquals("2020-01-01T00:00:00Z", out.get("lmt"),
                "lastModifiedTime 必须刷新 —— 修复前因 `&& blank` 永不更新");
        assertNotEquals("user-1", out.get("lmb"),
                "lastModifiedBy 必须刷新为本次操作者 —— 修复前因 `&& blank` 永不更新");
    }

    @Test
    void createdBy_usesAuthenticatedUserWhenPresent() {
        UUID uid = UUID.randomUUID();
        var principal = new com.nocobase.auth.JwtAuthFilter.AuthenticatedUser(uid, "alice", "tenant_default");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        Map<String, Object> out = service.applyFieldConstraints(meta(), Map.of("title", "v1"), null, true);
        assertEquals(uid.toString(), out.get("cb"), "createdBy 应记录真实用户 id，而非 \"system\"");
        assertEquals(uid.toString(), out.get("lmb"));
    }

    @Test
    void autoFieldRejectsManualWriteOnUpdate() {
        Map<String, Object> data = new HashMap<>();
        data.put("title", "v2");
        data.put("cb", "someone-else");   // 手工写自动字段 → 更新场景应 400
        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> service.applyFieldConstraints(meta(), data, "some-id", false));
        assertEquals(400, ex.getStatusCode().value());
    }
}
