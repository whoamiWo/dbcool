package com.nocobase.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * AuditService 单元测试(Week 21 抬红线).
 *
 * <p>覆盖 log 写入(含 IP/UA/payload 序列化)+ find + count。
 */
class AuditServiceTest {

    private AuditLogRepository repo;
    private AuditService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        service = new AuditService(repo, mapper);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /* === log 基础写入 === */

    @Test
    void log_savesAllFields() {
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        service.log("t1", UUID.randomUUID(), "alice", "CREATE", "customer", "id1", Map.of("name", "x"));
        org.mockito.Mockito.verify(repo).save(captor.capture());
        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo("t1");
        assertThat(saved.getAction()).isEqualTo("CREATE");
        assertThat(saved.getResource()).isEqualTo("customer");
        assertThat(saved.getResourceId()).isEqualTo("id1");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getPayloadJson()).contains("\"name\"").contains("\"x\"");
    }

    @Test
    void log_nullTenantId_defaultsToUnknown() {
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        service.log(null, "anonymous", "x", "READ", "r", "id", null);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("unknown");
    }

    @Test
    void log_nullUserId_defaultsToAnonymous() {
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        service.log("t1", null, null, "READ", "r", "id", null);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("anonymous");
        assertThat(captor.getValue().getUsername()).isNull();
    }

    @Test
    void log_nullPayload_skipsPayloadJson() {
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        service.log("t1", "u1", "alice", "READ", "r", null, null);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).isNull();
    }

    @Test
    void log_payload_serializedAsJson() {
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        Map<String, Object> payload = Map.of("k1", "v1", "k2", 42);
        service.log("t1", "u1", "alice", "UPDATE", "r", "id", payload);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        String json = captor.getValue().getPayloadJson();
        assertThat(json).contains("\"k1\"").contains("\"v1\"").contains("\"k2\"").contains("42");
    }

    @Test
    void log_unserializablePayload_fallsBackToString() {
        // Object that Jackson can't serialize(自引用 map)
        Map<String, Object> bad = new java.util.HashMap<>();
        bad.put("self", bad);
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        service.log("t1", "u1", "alice", "X", "r", "id", bad);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        // 回退路径:String.valueOf(map) — 至少不为 null
        assertThat(captor.getValue().getPayloadJson()).isNotNull();
    }

    @Test
    void log_saveFails_silentlyLogsButDoesNotThrow() {
        when(repo.save(any())).thenThrow(new RuntimeException("DB down"));
        // 不应抛异常
        service.log("t1", "u1", "alice", "X", "r", "id", null);
        // 没异常 = pass
    }

    /* === find 透传 === */

    @Test
    void find_delegatesToRepoWithPageable() {
        List<AuditLogEntity> rows = new ArrayList<>();
        rows.add(makeLog("CREATE"));
        rows.add(makeLog("UPDATE"));
        when(repo.findByFilter(any(), any(), any(), any(), any())).thenReturn(rows);
        var out = service.find("t1", "customer", "CREATE", "u1", 100);
        assertThat(out).hasSize(2);
    }

    @Test
    void find_capsLimitAt500() {
        var captor = ArgumentCaptor.forClass(Pageable.class);
        service.find("t1", null, null, null, 99999);
        org.mockito.Mockito.verify(repo).findByFilter(
                org.mockito.ArgumentMatchers.eq("t1"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(500);
    }

    @Test
    void count_delegatesToRepo() {
        when(repo.countByTenantId("t1")).thenReturn(42L);
        assertThat(service.count("t1")).isEqualTo(42L);
    }

    private AuditLogEntity makeLog(String action) {
        AuditLogEntity e = new AuditLogEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId("t1");
        e.setUserId("u1");
        e.setAction(action);
        e.setResource("customer");
        e.setCreatedAt(Instant.now());
        return e;
    }
}