package com.nocobase.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

/**
 * AuditController 单测(Week 31).
 */
class AuditControllerTest {

    private AuditService service;
    private AuditController controller;
    private AuthenticatedUser testUser;

    @BeforeEach
    void setUp() {
        service = org.mockito.Mockito.mock(AuditService.class);
        controller = new AuditController(service);
        testUser = new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(testUser, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AuditLogEntity makeLog() {
        AuditLogEntity e = new AuditLogEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId("tenant_default");
        e.setUserId(testUser.userId().toString());
        e.setUsername("alice");
        e.setAction("CREATE");
        e.setResource("posts");
        e.setResourceId("p-1");
        e.setPayloadJson("{}");
        e.setIp("127.0.0.1");
        e.setUserAgent("ua");
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Test
    void list_returnsLogsAndTotal() {
        when(service.find(eq("tenant_default"), any(), any(), any(), anyInt()))
                .thenReturn(List.of(makeLog(), makeLog()));
        when(service.count("tenant_default")).thenReturn(100L);

        Map<String, Object> resp = controller.list(null, null, null, 50, testUser);

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(100L, data.get("total"));
        assertEquals(2, ((List<?>) data.get("logs")).size());
    }

    @Test
    void list_withFilters_passesToService() {
        when(service.find(eq("tenant_default"), eq("posts"), eq("CREATE"), eq("alice"), eq(50)))
                .thenReturn(List.of());
        when(service.count(anyString())).thenReturn(0L);

        controller.list("posts", "CREATE", "alice", 50, testUser);

        org.mockito.Mockito.verify(service).find("tenant_default", "posts", "CREATE", "alice", 50);
    }

    @Test
    void list_defaultLimitUsed() {
        when(service.find(anyString(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(service.count(anyString())).thenReturn(0L);

        controller.list(null, null, null, 50, testUser);

        org.mockito.Mockito.verify(service).find(anyString(), any(), any(), any(), eq(50));
    }

    @Test
    void list_logDtoIncludesAllFields() {
        AuditLogEntity e = makeLog();
        when(service.find(any(), any(), any(), any(), anyInt())).thenReturn(List.of(e));
        when(service.count(anyString())).thenReturn(1L);

        Map<String, Object> resp = controller.list(null, null, null, 50, testUser);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> logs = (List<Map<String, Object>>) data.get("logs");
        assertEquals(1, logs.size());
        Map<String, Object> log = logs.get(0);
        assertNotNull(log.get("id"));
        assertEquals("alice", log.get("username"));
        assertEquals("CREATE", log.get("action"));
        assertEquals("posts", log.get("resource"));
        assertEquals("p-1", log.get("resource_id"));
        assertEquals("127.0.0.1", log.get("ip"));
        assertNotNull(log.get("created_at"));
    }
}
