package com.nocobase.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * AuditService 单测(Week 31).
 * 覆盖 log 全部路径(IP/User-Agent 捕获 + null message fallback + tenantId fallback)
 * + find + count + clientIp 私有方法间接测试.
 */
class AuditServiceTest {

    private AuditLogRepository repo;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        service = new AuditService(repo, new ObjectMapper());
        when(repo.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequest(String xff, String ua) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (xff != null) req.addHeader("X-Forwarded-For", xff);
        if (ua != null) req.addHeader("User-Agent", ua);
        req.setRemoteAddr("10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void log_basicCase_savesEntity() {
        UUID userId = UUID.randomUUID();
        service.log("tenant_default", userId, "alice", "CREATE", "posts", "p-1", Map.of("k", "v"));

        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        AuditLogEntity e = cap.getValue();
        assertEquals("tenant_default", e.getTenantId());
        assertEquals(userId.toString(), e.getUserId());
        assertEquals("alice", e.getUsername());
        assertEquals("CREATE", e.getAction());
        assertEquals("posts", e.getResource());
        assertEquals("p-1", e.getResourceId());
        assertNotNull(e.getPayloadJson());
    }

    @Test
    void log_nullTenant_fallsBackToUnknown() {
        service.log(null, UUID.randomUUID(), null, "x", "y", "z", null);
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        assertEquals("unknown", cap.getValue().getTenantId());
        assertEquals(null, cap.getValue().getUsername());
    }

    @Test
    void log_nullUserId_fallsBackToAnonymous() {
        service.log("tenant", null, "alice", "x", "y", "z", null);
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        assertEquals("anonymous", cap.getValue().getUserId());
    }

    @Test
    void log_capturesIpFromRemoteAddr() {
        bindRequest(null, "test-ua");
        service.log("tenant", UUID.randomUUID(), "u", "x", "y", "z", null);
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        assertEquals("10.0.0.1", cap.getValue().getIp());
        assertEquals("test-ua", cap.getValue().getUserAgent());
    }

    @Test
    void log_prefersXForwardedForOverRemoteAddr() {
        bindRequest("203.0.113.5, 10.0.0.2", null);
        service.log("tenant", UUID.randomUUID(), "u", "x", "y", "z", null);
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        // X-Forwarded-For 第一段
        assertEquals("203.0.113.5", cap.getValue().getIp());
    }

    @Test
    void log_truncatesLongUserAgent() {
        String longUa = "u".repeat(300);
        bindRequest(null, longUa);
        service.log("tenant", UUID.randomUUID(), "u", "x", "y", "z", null);
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        // 限制 250 字符
        assertEquals(250, cap.getValue().getUserAgent().length());
    }

    @Test
    void log_noRequestContext_capturesNoIp() {
        // 不调用 bindRequest — 没有 request context
        service.log("tenant", UUID.randomUUID(), "u", "x", "y", "z", null);
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        assertEquals(null, cap.getValue().getIp());
        assertEquals(null, cap.getValue().getUserAgent());
    }

    @Test
    void log_serializeFailure_fallsBackToString() {
        // payload 含自引用,ObjectMapper 会抛 JsonProcessingException
        Object selfRef = new Object() {
            @Override public String toString() { return "TOSTRING_FALLBACK"; }
        };
        service.log("tenant", UUID.randomUUID(), "u", "x", "y", "z", selfRef);
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        // payloadJson 不会是 null(因为 fallback 到 String.valueOf)
        assertNotNull(cap.getValue().getPayloadJson());
    }

    @Test
    void log_repoThrows_silentlySwallowed() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        // 不应抛
        service.log("tenant", UUID.randomUUID(), "u", "x", "y", "z", null);
    }

    @Test
    void find_delegatesToRepo() {
        when(repo.findByFilter(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(new AuditLogEntity()));
        List<AuditLogEntity> r = service.find("t", "r", "a", "u", 100);
        assertEquals(1, r.size());
    }

    @Test
    void find_clampsLimitTo500() {
        when(repo.findByFilter(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        service.find("t", null, null, null, 9999);
        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repo).findByFilter(any(), any(), any(), any(), cap.capture());
        // PageRequest size 限制到 500
        assertEquals(Math.min(9999, 500), cap.getValue().getPageSize());
    }

    @Test
    void count_delegatesToRepo() {
        when(repo.countByTenantId("t")).thenReturn(42L);
        assertEquals(42L, service.count("t"));
    }

    private static <T> T mock(Class<T> c) {
        return org.mockito.Mockito.mock(c);
    }
}
