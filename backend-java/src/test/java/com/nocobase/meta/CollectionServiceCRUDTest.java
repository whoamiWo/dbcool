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

/**
 * PHASE104: CollectionService CRUD 路径覆盖（补 JaCoCo 缺口）。
 *
 * <p>目标方法：insertRecord / listRecords / getRecord / updateRecord /
 * deleteRecord / batchInsert / batchUpdate / batchDelete / addField /
 * removeField / renameField / aggregate。
 *
 * <p>策略：纯 Mockito（参照 {@code CollectionServiceB3Test} 的既有模式），
 * 不加载 Spring 上下文 —— 快且稳定，CollectionService 自身的
 * 校验/序列化/租户校验逻辑被真实执行。
 */
public class CollectionServiceCRUDTest {

    private CollectionRepository repository;
    private DynamicTableManager tableManager;
    private AsyncMigrationService migrationService;
    private CollectionService service;

    private static final String TENANT_ID = "tenant_default";
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
        // InverseRelationManager（4 参构造器内部 new）会调 repository.save
        when(repository.save(any(CollectionMetaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // void 方法用 doNothing
        doNothing().when(tableManager).createTable(anyString());
        doNothing().when(tableManager).insertRecord(anyString(), anyString(), anyString());
        doNothing().when(tableManager).addPhysicalColumn(anyString(), anyString(), anyString());
        doNothing().when(tableManager).dropPhysicalColumn(anyString(), anyString());
        doNothing().when(tableManager).renamePhysicalColumn(anyString(), anyString(), anyString());

        when(tableManager.updateRecord(anyString(), anyString(), anyString())).thenReturn(1);
        when(tableManager.deleteRecord(anyString(), anyString())).thenReturn(1);
        when(tableManager.listRecords(anyString(), anyInt(), any(), anyList()))
                .thenReturn(List.of("{\"data\":{\"id\":\"1\",\"amount\":100}}"));
        when(tableManager.getRecord(anyString(), anyString()))
                .thenReturn(Optional.of("{\"data\":{\"id\":\"1\",\"amount\":100}}"));
        when(tableManager.aggregate(anyString(), anyList(), anyList(), anyList()))
                .thenReturn(List.of(Map.of("_id", "X", "total", 30L)));
    }

    @Test
    void insertRecord_basic() {
        UUID id = service.insertRecord(COLLECTION, Map.of("amount", 100), TENANT_ID);
        assertNotNull(id);
    }

    @Test
    void insertRecord_nullTenant_rejected() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.insertRecord(COLLECTION, Map.of("amount", 1), null));
    }

    @Test
    void insertRecord_blankTenant_rejected() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.insertRecord(COLLECTION, Map.of("amount", 1), "   "));
    }

    @Test
    void insertRecord_wrongTenant_forbidden() {
        assertThrows(Exception.class,
                () -> service.insertRecord(COLLECTION, Map.of("amount", 1), "other_tenant"));
    }

    @Test
    void getRecord_returnsRecord() {
        Map<String, Object> record = service.getRecord(COLLECTION, UUID.randomUUID().toString(), TENANT_ID);
        assertNotNull(record);
        assertEquals(100, ((Number) record.get("amount")).intValue());
    }

    @Test
    void listRecords_basic() {
        List<Map<String, Object>> records = service.listRecords(COLLECTION, TENANT_ID, 10, null, null);
        assertEquals(1, records.size());
    }

    @Test
    void listRecords_withFilter_matches() {
        when(tableManager.listRecords(anyString(), anyInt(), any(), anyList()))
                .thenReturn(List.of(
                        "{\"data\":{\"id\":\"1\",\"status\":\"active\"}}",
                        "{\"data\":{\"id\":\"2\",\"status\":\"inactive\"}}"));

        List<Map<String, Object>> records = service.listRecords(COLLECTION, TENANT_ID, 10, null,
                List.of(new CollectionService.FilterRule("status", "eq", "active")));

        assertEquals(1, records.size());
        assertEquals("active", records.get(0).get("status"));
    }

    @Test
    void listRecords_wrongTenant_forbidden() {
        assertThrows(Exception.class, () ->
                service.listRecords(COLLECTION, "other_tenant", 10, null, null));
    }

    @Test
    void updateRecord_success() {
        boolean updated = service.updateRecord(COLLECTION, UUID.randomUUID().toString(),
                Map.of("amount", 200), TENANT_ID);
        assertTrue(updated);
    }

    @Test
    void updateRecord_noRowsAffected_returnsFalse() {
        when(tableManager.updateRecord(anyString(), anyString(), anyString())).thenReturn(0);
        boolean updated = service.updateRecord(COLLECTION, UUID.randomUUID().toString(),
                Map.of("amount", 200), TENANT_ID);
        assertFalse(updated);
    }

    @Test
    void deleteRecord_success() {
        assertTrue(service.deleteRecord(COLLECTION, UUID.randomUUID().toString(), TENANT_ID));
    }

    @Test
    void batchInsert_returnsCount() {
        List<Map<String, Object>> dataList = List.of(
                Map.of("amount", 1), Map.of("amount", 2), Map.of("amount", 3));
        assertEquals(3, service.batchInsert(COLLECTION, dataList, TENANT_ID));
    }

    @Test
    void batchUpdate_returnsUpdatedIds() {
        List<CollectionService.BatchUpdateItem> items = List.of(
                new CollectionService.BatchUpdateItem(UUID.randomUUID().toString(), Map.of("amount", 100)),
                new CollectionService.BatchUpdateItem(UUID.randomUUID().toString(), Map.of("amount", 200)));
        List<String> updated = service.batchUpdate(COLLECTION, items, TENANT_ID);
        assertEquals(2, updated.size());
    }

    @Test
    void batchDelete_returnsCount() {
        List<String> ids = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        assertEquals(2, service.batchDelete(COLLECTION, ids, TENANT_ID));
    }

    @Test
    void addField_syncSuccess_returnsNull() {
        // addField 同步成功时返回 null（异步超时才返回 job_id）
        UUID fieldId = service.addField(COLLECTION,
                new FieldDef("newField", "text", false, "New Text Field", null),
                TENANT_ID, UUID.randomUUID());
        assertNull(fieldId);
    }

    @Test
    void removeField_syncSuccess_returnsNull() {
        service.addField(COLLECTION,
                new FieldDef("tempField", "text", false, "Temp Field", null),
                TENANT_ID, UUID.randomUUID());
        UUID removed = service.removeField(COLLECTION, "tempField", TENANT_ID, UUID.randomUUID());
        assertNull(removed);
    }

    @Test
    void removeField_unknownField_notFound() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.removeField(COLLECTION, "noSuchField", TENANT_ID, UUID.randomUUID()));
    }

    @Test
    void renameField_syncSuccess_returnsNull() {
        service.addField(COLLECTION,
                new FieldDef("oldName", "text", false, "Old Name", null),
                TENANT_ID, UUID.randomUUID());
        UUID renamed = service.renameField(COLLECTION, "oldName", "newName", TENANT_ID, UUID.randomUUID());
        assertNull(renamed);
    }

    @Test
    void renameField_sameName_badRequest() {
        service.addField(COLLECTION,
                new FieldDef("sameName", "text", false, "Same Name", null),
                TENANT_ID, UUID.randomUUID());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.renameField(COLLECTION, "sameName", "sameName", TENANT_ID, UUID.randomUUID()));
    }

    @Test
    void aggregate_delegatesToTableManager() {
        List<Map<String, Object>> result = service.aggregate(COLLECTION,
                List.of("group"),
                List.of(new DynamicTableManager.AggSpec("total", "sum", "amount")),
                null,
                TENANT_ID);
        // tableManager.aggregate 被调用且结果原样返回
        org.mockito.Mockito.verify(tableManager).aggregate(
                org.mockito.ArgumentMatchers.eq(COLLECTION),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.isNull());
        assertNotNull(result);
    }

    @Test
    void aggregate_wrongTenant_forbidden() {
        assertThrows(Exception.class, () ->
                service.aggregate(COLLECTION, List.of("group"), null, null, "other_tenant"));
    }
}
