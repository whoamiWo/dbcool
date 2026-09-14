package com.nocobase.acl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.config.SecurityConfig;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RowAclController WebMvcTest(Week 26 抬红线).
 * 覆盖 /api/admin/row-acl 5 个端点:
 *   GET    /api/admin/row-acl
 *   GET    /api/admin/row-acl/by-collection/{collection}
 *   POST   /api/admin/row-acl
 *   PUT    /api/admin/row-acl/{id}
 *   DELETE /api/admin/row-acl/{id}
 */
@WebMvcTest(RowAclController.class)
@AutoConfigureMockMvc(addFilters = false)
class RowAclControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private AclRowPolicyRepository repository;
    // ObjectMapper 由 Spring 注入(不要 @MockBean,否则 .reader() 返回 null)

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void loginAs(UUID userId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "alice", "tenant_default");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private AclRowPolicyEntity policy(UUID id, String tenantId) {
        AclRowPolicyEntity p = new AclRowPolicyEntity();
        p.setId(id);
        p.setTenantId(tenantId);
        p.setCollection("customer");
        p.setPrincipalType("role");
        p.setPrincipalId("admin");
        p.setAction("read");
        p.setExpression("{\"field\":\"dept\",\"op\":\"eq\",\"value\":\"sales\"}");
        p.setPriority(10);
        p.setEnabled(true);
        p.setDescription("sales only");
        p.setCreatedAt(OffsetDateTime.now());
        p.setUpdatedAt(OffsetDateTime.now());
        return p;
    }

    @Test
    void list_filtersByTenant() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        UUID pid = UUID.randomUUID();
        when(repository.findAll()).thenReturn(List.of(
                policy(pid, "tenant_default"),
                policy(UUID.randomUUID(), "other_tenant")  // 应被过滤
        ));

        mockMvc.perform(get("/api/admin/row-acl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(pid.toString()));
    }

    @Test
    void byCollection_returnsPoliciesForCollection() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        UUID pid = UUID.randomUUID();
        when(repository.findByTenantIdAndCollectionOrderByPriorityDescCreatedAtAsc("tenant_default", "customer"))
                .thenReturn(List.of(policy(pid, "tenant_default")));

        mockMvc.perform(get("/api/admin/row-acl/by-collection/{collection}", "customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].collection").value("customer"));
    }

    @Test
    void create_validRequest_returnsPolicy() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);

        String body = """
                {
                  "collection": "customer",
                  "principal_type": "role",
                  "principal_id": "admin",
                  "action": "read",
                  "expression": {"field":"dept","op":"eq","value":"sales"},
                  "priority": 10,
                  "enabled": true,
                  "description": "sales only"
                }
                """;
        mockMvc.perform(post("/api/admin/row-acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.collection").value("customer"))
                .andExpect(jsonPath("$.data.principal_type").value("role"))
                .andExpect(jsonPath("$.data.action").value("read"))
                .andExpect(jsonPath("$.data.priority").value(10))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void update_existingPolicy_returnsUpdated() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        loginAs(uid);
        when(repository.findById(pid)).thenReturn(java.util.Optional.of(policy(pid, "tenant_default")));

        String body = """
                {
                  "collection": "order",
                  "principal_type": "user",
                  "principal_id": "alice",
                  "action": "write",
                  "expression": {"all": true},
                  "priority": 5,
                  "enabled": false
                }
                """;
        mockMvc.perform(put("/api/admin/row-acl/{id}", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.collection").value("order"))
                .andExpect(jsonPath("$.data.action").value("write"))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        loginAs(uid);
        when(repository.findById(pid)).thenReturn(java.util.Optional.empty());

        String body = """
                {"collection":"order","principal_type":"user","principal_id":"u","action":"read","expression":{}}
                """;
        mockMvc.perform(put("/api/admin/row-acl/{id}", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_otherTenant_returns403() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        loginAs(uid);
        when(repository.findById(pid)).thenReturn(java.util.Optional.of(policy(pid, "other_tenant")));

        String body = """
                {"collection":"order","principal_type":"user","principal_id":"u","action":"read","expression":{}}
                """;
        mockMvc.perform(put("/api/admin/row-acl/{id}", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_existing_returnsSuccess() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        loginAs(uid);
        when(repository.findById(pid)).thenReturn(java.util.Optional.of(policy(pid, "tenant_default")));

        mockMvc.perform(delete("/api/admin/row-acl/{id}", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(pid.toString()));
    }

    @Test
    void delete_otherTenant_returns403() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        loginAs(uid);
        when(repository.findById(pid)).thenReturn(java.util.Optional.of(policy(pid, "other_tenant")));

        mockMvc.perform(delete("/api/admin/row-acl/{id}", pid))
                .andExpect(status().isForbidden());
    }
}
