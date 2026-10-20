package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE125 版本服务测试 —— 纯逻辑 + mock 仓储,不依赖真实数据库。
 */
class RecordVersionServiceTest {

    private static final String TENANT = "tenant_default";
    private static final String COLL = "orders";
    private static final String REC_ID = "11111111-1111-1111-1111-111111111111";

    private RecordVersionRepository repository;
    private RecordVersionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RecordVersionRepository.class);
        service = new RecordVersionService(repository, new ObjectMapper());
        // save 原样返回,便于断言写入内容
        when(repository.save(any(RecordVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void computeDiff_detectsModifiedField() {
        String diff = service.computeDiff(Map.of("status", "draft"), Map.of("status", "paid"));

        assertThat(diff).contains("\"field\":\"status\"")
                .contains("\"oldValue\":\"draft\"")
                .contains("\"newValue\":\"paid\"");
    }

    @Test
    void computeDiff_detectsAddedField() {
        String diff = service.computeDiff(Map.of("a", "1"), Map.of("a", "1", "b", "2"));

        // 新增字段:oldValue 为 null
        assertThat(diff).contains("\"field\":\"b\"").contains("\"newValue\":\"2\"");
    }

    @Test
    void computeDiff_noChange_returnsEmptyArray() {
        Map<String, Object> same = Map.of("x", "1");

        assertThat(service.computeDiff(same, same)).isEqualTo("[]");
    }

    @Test
    void record_firstVersion_startsAtOne() {
        when(repository.findTopByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
                anyString(), anyString(), any(UUID.class))).thenReturn(Optional.empty());

        Optional<RecordVersionEntity> saved = service.record(
                TENANT, COLL, REC_ID, Map.of("status", "paid"), null, "INSERT", UUID.randomUUID());

        assertThat(saved).isPresent();
        assertThat(saved.get().getVersion()).isEqualTo(1);
        assertThat(saved.get().getOperation()).isEqualTo("INSERT");
        assertThat(saved.get().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void record_incrementsVersionFromLatest() {
        RecordVersionEntity latest = new RecordVersionEntity();
        latest.setVersion(3);
        when(repository.findTopByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
                anyString(), anyString(), any(UUID.class))).thenReturn(Optional.of(latest));

        Optional<RecordVersionEntity> saved = service.record(
                TENANT, COLL, REC_ID, Map.of("status", "paid"), Map.of("status", "draft"),
                "UPDATE", UUID.randomUUID());

        assertThat(saved).isPresent();
        assertThat(saved.get().getVersion()).isEqualTo(4); // 3 + 1
        // diff 记录了 status: draft → paid
        assertThat(saved.get().getChangedFields()).contains("\"oldValue\":\"draft\"");
    }

    @Test
    void record_queriesWithTenantScope_isolation() {
        when(repository.findTopByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
                anyString(), anyString(), any(UUID.class))).thenReturn(Optional.empty());

        service.record(TENANT, COLL, REC_ID, Map.of("k", "v"), null, "UPDATE", UUID.randomUUID());

        // 版本查询必须带 tenantId —— 防止跨租户读到他人版本(多租户隔离)
        verify(repository).findTopByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
                eq(TENANT), eq(COLL), any(UUID.class));
    }

    @Test
    void record_invalidRecordId_returnsEmptyWithoutThrowing() {
        // 留痕失败不得影响业务主流程
        assertThat(service.record(TENANT, COLL, "not-a-uuid", Map.of("k", "v"),
                null, "UPDATE", UUID.randomUUID())).isEmpty();
    }

    @Test
    void listVersions_delegatesWithTenantScope() {
        when(repository.findByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
                anyString(), anyString(), any(UUID.class))).thenReturn(java.util.List.of());

        service.listVersions(TENANT, COLL, REC_ID);

        verify(repository).findByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
                eq(TENANT), eq(COLL), any(UUID.class));
    }

    @Test
    void getVersionSnapshot_roundTripsMap() {
        RecordVersionEntity v = new RecordVersionEntity();
        v.setVersion(2);
        v.setDataJson("{\"status\":\"archived\"}");
        when(repository.findByTenantIdAndCollectionNameAndRecordIdAndVersion(
                anyString(), anyString(), any(UUID.class), anyInt())).thenReturn(Optional.of(v));

        Optional<Map<String, Object>> snap = service.getVersionSnapshot(TENANT, COLL, REC_ID, 2);

        assertThat(snap).isPresent();
        assertThat(snap.get()).containsEntry("status", "archived");
    }

    @Test
    void deleteVersions_clearsHistoryOnPurge() {
        service.deleteVersions(TENANT, COLL, REC_ID);

        verify(repository).deleteByTenantIdAndCollectionNameAndRecordId(
                eq(TENANT), eq(COLL), any(UUID.class));
    }
}
