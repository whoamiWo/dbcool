package com.nocobase.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.config.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RoleAclController WebMvcTest(Week 27 抬红线).
 * 覆盖 /api/admin/roles 4 + tree + inheritance + /api/admin/acl 5 = 11 endpoints.
 */
@WebMvcTest(RoleAclController.class)
@AutoConfigureMockMvc(addFilters = false)
class RoleAclControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private RoleRepository roleRepository;
    @MockBean private AclPolicyRepository aclRepository;

    private RoleEntity role(UUID id, String name, UUID parentId) {
        RoleEntity r = new RoleEntity();
        r.setId(id);
        r.setName(name);
        r.setDescription(name + " desc");
        r.setTenantId("tenant_default");
        r.setParentRoleId(parentId);
        r.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
        return r;
    }

    private AclPolicyEntity policy(UUID id, UUID roleId, AclPolicyEntity.Type type, AclPolicyEntity.Action action) {
        AclPolicyEntity p = new AclPolicyEntity();
        p.setId(id);
        p.setRoleId(roleId);
        p.setType(type);
        p.setSubject("customer");
        p.setAction(action);
        p.setConfigJson("{\"hidden\":[\"ssn\"]}");
        p.setTenantId("tenant_default");
        p.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
        return p;
    }

    // ============ Roles CRUD ============

    @Test
    void listRoles_returnsAllForDefaultTenant() throws Exception {
        UUID r1 = UUID.randomUUID();
        when(roleRepository.findByTenantIdOrderByCreatedAt("tenant_default"))
                .thenReturn(List.of(role(r1, "admin", null), role(UUID.randomUUID(), "viewer", r1)));

        mockMvc.perform(get("/api/admin/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("admin"))
                .andExpect(jsonPath("$.data[0].parent_role_id").doesNotExist())
                .andExpect(jsonPath("$.data[1].name").value("viewer"))
                .andExpect(jsonPath("$.data[1].parent_role_id").value(r1.toString()));
    }

    @Test
    void createRole_validRequest_returns201() throws Exception {
        when(roleRepository.existsByNameAndTenantId("ops", "tenant_default")).thenReturn(false);
        when(roleRepository.save(any())).thenAnswer(inv -> {
            RoleEntity r = inv.getArgument(0);
            return r;
        });

        String body = """
                {"name":"ops","description":"ops team"}
                """;
        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("ops"))
                .andExpect(jsonPath("$.data.tenant_id").value("tenant_default"));
    }

    @Test
    void createRole_duplicateName_returns409() throws Exception {
        when(roleRepository.existsByNameAndTenantId("ops", "tenant_default")).thenReturn(true);

        String body = """
                {"name":"ops","description":"ops team"}
                """;
        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void createRole_blankName_returns400() throws Exception {
        String body = """
                {"name":"","description":"x"}
                """;
        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_cycleInInheritance_returns400() throws Exception {
        // 测试 assertNoCycle 的 ancestor-contains 分支:在 updateRole 中 roleId 来自 path,
        // 可控。setup:把 role "child" 的 parent 设为 candidate,
        // candidate 的祖先链 [candidate, child](包含 child 自己) → cycle → 400.
        UUID childId = UUID.randomUUID();
        UUID candidateParentId = UUID.randomUUID();
        when(roleRepository.findByIdAndTenantId(childId, "tenant_default"))
                .thenReturn(Optional.of(role(childId, "child", null)));
        when(roleRepository.findSelfAndAncestors(candidateParentId, "tenant_default"))
                .thenReturn(List.of(candidateParentId, childId));  // child 是 candidate 的祖先 → cycle

        String body = String.format("""
                {"parent_role_id":"%s"}
                """, candidateParentId);
        mockMvc.perform(put("/api/admin/roles/{id}", childId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_existing_returnsUpdated() throws Exception {
        UUID rid = UUID.randomUUID();
        when(roleRepository.findByIdAndTenantId(rid, "tenant_default"))
                .thenReturn(Optional.of(role(rid, "admin", null)));
        when(roleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String body = """
                {"name":"admin_v2","description":"updated"}
                """;
        mockMvc.perform(put("/api/admin/roles/{id}", rid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("admin_v2"))
                .andExpect(jsonPath("$.data.description").value("updated"));
    }

    @Test
    void updateRole_notFound_returns404() throws Exception {
        UUID rid = UUID.randomUUID();
        when(roleRepository.findByIdAndTenantId(rid, "tenant_default")).thenReturn(Optional.empty());

        String body = """
                {"name":"x"}
                """;
        mockMvc.perform(put("/api/admin/roles/{id}", rid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRole_clearsChildrenAndAcls_thenDeletes() throws Exception {
        UUID rid = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        when(roleRepository.findByParentRoleId(rid))
                .thenReturn(List.of(role(childId, "viewer", rid)));
        when(roleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(aclRepository.findByRoleIdAndTenantId(rid, "tenant_default"))
                .thenReturn(List.of(policy(UUID.randomUUID(), rid, AclPolicyEntity.Type.FIELD, AclPolicyEntity.Action.READ)));

        mockMvc.perform(delete("/api/admin/roles/{id}", rid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("deleted"));
    }

    // ============ Role Tree + Inheritance ============

    @Test
    void roleTree_returnsNestedStructure() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        RoleEntity root = role(rootId, "admin", null);
        RoleEntity child = role(childId, "viewer", rootId);
        when(roleRepository.findByTenantIdOrderByCreatedAt("tenant_default"))
                .thenReturn(List.of(root, child));

        mockMvc.perform(get("/api/admin/roles/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("admin"))
                .andExpect(jsonPath("$.data[0].children.length()").value(1))
                .andExpect(jsonPath("$.data[0].children[0].name").value("viewer"));
    }

    @Test
    void roleInheritanceChain_returnsAncestors() throws Exception {
        UUID rid = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(roleRepository.findByIdAndTenantId(rid, "tenant_default"))
                .thenReturn(Optional.of(role(rid, "viewer", parentId)));
        when(roleRepository.findSelfAndAncestors(rid, "tenant_default"))
                .thenReturn(List.of(rid, parentId));
        when(roleRepository.findById(rid))
                .thenReturn(Optional.of(role(rid, "viewer", parentId)));
        when(roleRepository.findById(parentId))
                .thenReturn(Optional.of(role(parentId, "admin", null)));

        mockMvc.perform(get("/api/admin/roles/{id}/inheritance", rid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role.name").value("viewer"))
                .andExpect(jsonPath("$.data.depth").value(1))
                .andExpect(jsonPath("$.data.chain.length()").value(2))
                .andExpect(jsonPath("$.data.chain[0].name").value("viewer"))
                .andExpect(jsonPath("$.data.chain[1].name").value("admin"));
    }

    @Test
    void roleInheritanceChain_roleNotFound_returns404() throws Exception {
        UUID rid = UUID.randomUUID();
        when(roleRepository.findByIdAndTenantId(rid, "tenant_default")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/roles/{id}/inheritance", rid))
                .andExpect(status().isNotFound());
    }

    // ============ ACL Policies ============

    @Test
    void listAcl_filtersByRoleId() throws Exception {
        UUID roleId = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        when(aclRepository.findByRoleIdAndTenantId(roleId, "tenant_default"))
                .thenReturn(List.of(policy(pid, roleId, AclPolicyEntity.Type.FIELD, AclPolicyEntity.Action.READ)));

        mockMvc.perform(get("/api/admin/acl").param("roleId", roleId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].type").value("FIELD"))
                .andExpect(jsonPath("$.data[0].action").value("READ"))
                .andExpect(jsonPath("$.data[0].role_id").value(roleId.toString()));
    }

    @Test
    void createAcl_validRequest_returns201() throws Exception {
        UUID rid = UUID.randomUUID();
        when(roleRepository.findByIdAndTenantId(rid, "tenant_default"))
                .thenReturn(Optional.of(role(rid, "admin", null)));
        when(aclRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String body = """
                {"roleId":"%s","type":"field","subject":"customer","action":"read","config":"{\\"hidden\\":[\\"ssn\\"]}"}
                """.formatted(rid);
        mockMvc.perform(post("/api/admin/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.type").value("FIELD"));
    }

    @Test
    void createAcl_invalidActionEnum_returns500orBadRequest() throws Exception {
        // action 无效,Action.valueOf 会抛 IllegalArgumentException — 由 GlobalExceptionHandler 处理
        String body = """
                {"roleId":"%s","type":"action","subject":"customer","action":"INVALID"}
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/admin/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError());  // 全局异常
    }

    @Test
    void updateAcl_existing_returnsUpdated() throws Exception {
        UUID pid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();
        when(aclRepository.findByIdAndTenantId(pid, "tenant_default"))
                .thenReturn(Optional.of(policy(pid, rid, AclPolicyEntity.Type.FIELD, AclPolicyEntity.Action.READ)));
        when(aclRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String body = """
                {"action":"update","config":"{\\"hidden\\":[\\"salary\\"]}"}
                """;
        mockMvc.perform(put("/api/admin/acl/{id}", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.action").value("UPDATE"));
    }

    @Test
    void updateAcl_notFound_returns404() throws Exception {
        UUID pid = UUID.randomUUID();
        when(aclRepository.findByIdAndTenantId(pid, "tenant_default")).thenReturn(Optional.empty());

        String body = """
                {"action":"write"}
                """;
        mockMvc.perform(put("/api/admin/acl/{id}", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteAcl_returnsSuccess() throws Exception {
        UUID pid = UUID.randomUUID();

        mockMvc.perform(delete("/api/admin/acl/{id}", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("deleted"));
    }
}
