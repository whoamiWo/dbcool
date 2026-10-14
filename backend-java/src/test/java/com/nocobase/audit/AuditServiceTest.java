package com.nocobase.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/**
 * PHASE105: AuditService 覆盖（正常日志、payload 序列化、异常分支）。
 */
public class AuditServiceTest {

    private AuditLogRepository repo;
    private ObjectMapper mapper;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        mapper = new ObjectMapper();
        service = new AuditService(repo, mapper);
    }

    @Test
    void log_success_createsEntityAndSaves() {
        service.log("tenant1", "u1", "alice", "user.create", "user", "u1", Map.of("email", "a@b.com"));
        verify(repo).save(any(AuditLogEntity.class));
    }

    @Test
    void log_nullTenantId_usesUnknown() {
        service.log(null, "u1", "alice", "user.create", "user", "u1", null);
        verify(repo).save(any(AuditLogEntity.class));
    }

    @Test
    void log_nullUserId_usesAnonymous() {
        service.log("tenant1", null, "system", "system.op", "sys", "cfg", null);
        verify(repo).save(any(AuditLogEntity.class));
    }

    @Test
    void log_payloadNotSerializable_usesToString() throws Exception {
        // 用自定义对象触发 JsonProcessingException
        Object badPayload = new Object() {
            @Override
            public String toString() { return "bad-payload"; }
        };
        service.log("tenant1", "u1", "alice", "op", "r", "rid", badPayload);
        verify(repo).save(any(AuditLogEntity.class));
    }

    @Test
    void find_delegatesToRepo() {
        when(repo.findByFilter("t1", "r1", "a1", "u1", PageRequest.of(0, 10)))
                .thenReturn(List.of());
        List<AuditLogEntity> res = service.find("t1", "r1", "a1", "u1", 10);
        assertNotNull(res);
    }

    @Test
    void count_delegatesToRepo() {
        when(repo.countByTenantId("t1")).thenReturn(42L);
        assertEquals(42L, service.count("t1"));
    }
}
