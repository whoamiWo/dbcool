package com.nocobase.meta;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE105: CollectionService 记录读写 + 批量操作边界覆盖（补 JaCoCo 缺口）。
 *
 * <p>目标方法：updateRecord / deleteRecord / batchInsert / batchUpdate /
 * batchDelete / deleteMeta / getRecord / listRecords。
 *
 * <p>重点覆盖租户校验分支（403）、空 tenant 分支（403）、
 * 物理表清理失败降级路径（deleteMeta）。
 */
public class CollectionServiceRecordBatchTest {

    private CollectionRepository repository;
    private DynamicTableManager tableManager;
    private AsyncMigrationService migrationService;
    private CollectionService service;

    private static final String TENANT_ID = "tenant_default";
    private static final String OTHER_TENANT = "tenant_other";
    private static final String COLLECTION = "orders";

    private CollectionMetaEntity existing;

    @BeforeEach
    void setUp() {
        repository = mock(CollectionRepository.class);
        tableManager = mock(DynamicTableManager.class);
        migrationService = mock(AsyncMigrationService.class);
        service = new CollectionService(repository, tableManager, migrationService, new ObjectMapper());

        existing = new CollectionMetaEntity();
        existing.setId(UUID.randomUUID());
        existing.setName(COLLECTION);
        existing.setTitle("订单");
        existing.setTenantId(TENANT_ID);
        existing.setFieldsJson("[{\"name\":\"amount\",\"type\":\"number\"}]");
        when(repository.findByNameAndTenantId(COLLECTION, TENANT_ID)).thenReturn(Optional.of(existing));
        when(repository.findByName(COLLECTION)).thenReturn(Optional.of(existing));

        doNothing().when(tableManager).createTable(anyString());
        doNothing().when(tableManager).insertRecord(anyString(), anyString(), anyString());
        doNothing().when(tableManager).dropTable(anyString());

        when(tableManager.updateRecord(anyString(), anyString(), anyString())).thenReturn(1);
        when(tableManager.deleteRecord(anyString(), anyString())).thenReturn(1);
        when(tableManager.getRecord(anyString(), anyString()))
                .thenReturn(Optional.of("{\"data\":{\"id\":\"1\",\"amount\":100}}"));
        when(tableManager.listRecords(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of("{\"data\":{\"id\":\"1\",\"amount\":100}}"));
    }

    // ── updateRecord ────────────────────────────────────────────

    @Test
    void updateRecord_nullTenant_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.updateRecord(COLLECTION, "1", Map.of("amount", 2), null));
    }

    @Test
    void updateRecord_blankTenant_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.updateRecord(COLLECTION, "1", Map.of("amount", 2), "  "));
    }

    @Test
    void updateRecord_crossTenant_forbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateRecord(COLLECTION, "1", Map.of("amount", 2), OTHER_TENANT));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void updateRecord_success_mergesAndReturnsTrue() {
        boolean ok = service.updateRecord(COLLECTION, "1", Map.of("amount", 200), TENANT_ID);
        assertTrue(ok);
    }

    @Test
    void updateRecord_noMatch_returnsFalse() {
        when(tableManager.updateRecord(anyString(), anyString(), anyString())).thenReturn(0);
        boolean ok = service.updateRecord(COLLECTION, "1", Map.of("amount", 200), TENANT_ID);
        assertFalse(ok);
    }

    // ── deleteRecord ────────────────────────────────────────────

    @Test
    void deleteRecord_nullTenant_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.deleteRecord(COLLECTION, "1", null));
    }

    @Test
    void deleteRecord_crossTenant_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.deleteRecord(COLLECTION, "1", OTHER_TENANT));
    }

    @Test
    void deleteRecord_success() {
        assertTrue(service.deleteRecord(COLLECTION, "1", TENANT_ID));
    }

    @Test
    void deleteRecord_noMatch_returnsFalse() {
        when(tableManager.deleteRecord(anyString(), anyString())).thenReturn(0);
        assertFalse(service.deleteRecord(COLLECTION, "1", TENANT_ID));
    }

    // ── batchInsert ─────────────────────────────────────────────

    @Test
    void batchInsert_crossTenant_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.batchInsert(COLLECTION, List.of(Map.of("amount", 1)), OTHER_TENANT));
    }

    @Test
    void batchInsert_success_countsAll() {
        int n = service.batchInsert(COLLECTION,
                List.of(Map.of("amount", 1), Map.of("amount", 2), Map.of("amount", 3)),
                TENANT_ID);
        assertEquals(3, n);
    }

    // ── batchUpdate ─────────────────────────────────────────────

    @Test
    void batchUpdate_crossTenant_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.batchUpdate(COLLECTION,
                        List.of(new CollectionService.BatchUpdateItem("1", Map.of("amount", 9))),
                        OTHER_TENANT));
    }

    @Test
    void batchUpdate_success_returnsUpdatedIds() {
        List<String> ids = service.batchUpdate(COLLECTION,
                List.of(new CollectionService.BatchUpdateItem("1", Map.of("amount", 9)),
                        new CollectionService.BatchUpdateItem("2", Map.of("amount", 8))),
                TENANT_ID);
        assertEquals(List.of("1", "2"), ids);
    }

    // ── batchDelete ─────────────────────────────────────────────

    @Test
    void batchDelete_crossTenant_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.batchDelete(COLLECTION, List.of("1"), OTHER_TENANT));
    }

    @Test
    void batchDelete_success_countsDeleted() {
        int n = service.batchDelete(COLLECTION, List.of("1", "2"), TENANT_ID);
        assertEquals(2, n);
    }

    @Test
    void batchDelete_partialFailure_countsOnlySuccess() {
        when(tableManager.deleteRecord(COLLECTION, "2")).thenReturn(0);
        int n = service.batchDelete(COLLECTION, List.of("1", "2"), TENANT_ID);
        assertEquals(1, n);
    }

    // ── deleteMeta ──────────────────────────────────────────────

    @Test
    void deleteMeta_success_dropsPhysicalTable() {
        when(repository.deleteByName(COLLECTION)).thenReturn(1L);
        assertTrue(service.deleteMeta(COLLECTION, TENANT_ID));
        org.mockito.Mockito.verify(tableManager).dropTable(COLLECTION);
    }

    @Test
    void deleteMeta_alreadyDeleted_returnsFalse() {
        when(repository.deleteByName(COLLECTION)).thenReturn(0L);
        assertFalse(service.deleteMeta(COLLECTION, TENANT_ID));
        org.mockito.Mockito.verify(tableManager, org.mockito.Mockito.never()).dropTable(anyString());
    }

    @Test
    void deleteMeta_physicalDropFails_stillReturnsTrue() {
        when(repository.deleteByName(COLLECTION)).thenReturn(1L);
        org.mockito.Mockito.doThrow(new RuntimeException("drop failed"))
                .when(tableManager).dropTable(COLLECTION);
        // 物理表清理失败不得回滚元数据删除
        assertTrue(service.deleteMeta(COLLECTION, TENANT_ID));
    }

    // ── getRecord / listRecords ─────────────────────────────────

    @Test
    void getRecord_crossTenant_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.getRecord(COLLECTION, "1", OTHER_TENANT));
    }

    @Test
    void getRecord_notFound_throws() {
        when(tableManager.getRecord(anyString(), anyString())).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> service.getRecord(COLLECTION, "999", TENANT_ID));
    }

    @Test
    void listRecords_crossTenant_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.listRecords(COLLECTION, OTHER_TENANT, 10, null, null));
    }

    @Test
    void listRecords_success() {
        List<Map<String, Object>> rows = service.listRecords(COLLECTION, TENANT_ID, 10, null, null);
        assertEquals(1, rows.size());
    }
}
