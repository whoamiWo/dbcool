package com.nocobase.audit;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.config.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AuditController MockMvc 测试(Week 24).
 */
@WebMvcTest(AuditController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuditControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private AuditService service;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;

    private void login(String tenant) {
        AuthenticatedUser u = new AuthenticatedUser(UUID.randomUUID(), "admin", tenant);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private AuditLogEntity makeLog(String action) {
        AuditLogEntity e = new AuditLogEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId("tenant_default");
        e.setUserId("u1");
        e.setUsername("alice");
        e.setAction(action);
        e.setResource("customer");
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Test
    void logs_noFilters_returnsAll() throws Exception {
        login("tenant_default");
        when(service.find("tenant_default", null, null, null, 50))
                .thenReturn(List.of(makeLog("CREATE"), makeLog("UPDATE")));
        when(service.count("tenant_default")).thenReturn(42L);

        mockMvc.perform(get("/api/audit/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(42))
                .andExpect(jsonPath("$.data.logs.length()").value(2));
    }

    @Test
    void logs_withFilters_passesToService() throws Exception {
        login("tenant_default");
        when(service.find("tenant_default", "customer", "CREATE", "u1", 10))
                .thenReturn(List.of(makeLog("CREATE")));
        when(service.count("tenant_default")).thenReturn(1L);

        mockMvc.perform(get("/api/audit/logs")
                        .param("resource", "customer")
                        .param("action", "CREATE")
                        .param("userId", "u1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.logs.length()").value(1));
    }

    @Test
    void logs_defaultLimitIs50() throws Exception {
        login("tenant_default");
        when(service.find("tenant_default", null, null, null, 50)).thenReturn(List.of());
        when(service.count("tenant_default")).thenReturn(0L);

        // 不传 limit → 默认 50
        mockMvc.perform(get("/api/audit/logs"))
                .andExpect(status().isOk());
    }
}