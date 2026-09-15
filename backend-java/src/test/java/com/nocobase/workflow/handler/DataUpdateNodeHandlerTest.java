package com.nocobase.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.event.RecordChangeEvent;
import com.nocobase.meta.CollectionService;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowInstanceEntity;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DataUpdateNodeHandler 单元测试(Week 41 复核 D4b.5)。
 *
 * <p>该节点类型此前完全不存在 —— MVP_SCOPE 早已标 ✅ 但引擎没有写回能力。
 */
class DataUpdateNodeHandlerTest {

    private CollectionService collectionService;
    private DataUpdateNodeHandler handler;
    private WorkflowInstanceEntity instance;

    @BeforeEach
    void setUp() {
        collectionService = mock(CollectionService.class);
        handler = new DataUpdateNodeHandler(collectionService);
        instance = new WorkflowInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setWorkflowId(UUID.randomUUID());
        instance.setTenantId("tenant_default");
    }

    @Test
    void type_isDataUpdate() {
        assertThat(handler.type()).isEqualTo("DATA_UPDATE");
    }

    @Test
    void execute_explicitTarget_updatesRecord() {
        when(collectionService.updateRecord(anyString(), anyString(), any(), anyString()))
                .thenReturn(true);

        NodeOutcome out = handler.execute(new NodeExecutionContext(
                instance,
                Map.of("id", "n1", "type", "DATA_UPDATE", "config", Map.of(
                        "collection", "orders",
                        "recordId", "r-1",
                        "data", Map.of("status", "approved"))),
                null, null));

        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
        verify(collectionService).updateRecord(eq("orders"), eq("r-1"),
                eq(Map.of("status", "approved")), eq("tenant_default"));
    }

    @Test
    void execute_missingConfig_fallsBackToTriggerEvent() {
        when(collectionService.updateRecord(anyString(), anyString(), any(), anyString()))
                .thenReturn(true);
        RecordChangeEvent event = new RecordChangeEvent(
                RecordChangeEvent.ChangeType.CREATE, "orders", "r-9",
                Map.of("amount", 100), "tenant_default", UUID.randomUUID());

        handler.execute(new NodeExecutionContext(
                instance,
                Map.of("id", "n1", "type", "DATA_UPDATE", "config",
                        Map.of("data", Map.of("status", "new"))),
                null, event));

        verify(collectionService).updateRecord(eq("orders"), eq("r-9"),
                eq(Map.of("status", "new")), eq("tenant_default"));
    }

    @Test
    void execute_missingData_skipsWithoutUpdate() {
        NodeOutcome out = handler.execute(new NodeExecutionContext(
                instance,
                Map.of("id", "n1", "type", "DATA_UPDATE", "config",
                        Map.of("collection", "orders", "recordId", "r-1")),
                null, null));

        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
        verify(collectionService, never()).updateRecord(anyString(), anyString(), any(), anyString());
    }

    @Test
    void execute_noCollectionAndNoEvent_skips() {
        NodeOutcome out = handler.execute(new NodeExecutionContext(
                instance,
                Map.of("id", "n1", "type", "DATA_UPDATE", "config",
                        Map.of("data", Map.of("status", "x"))),
                null, null));

        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
        verify(collectionService, never()).updateRecord(anyString(), anyString(), any(), anyString());
    }

    @Test
    void execute_serviceThrows_returnsFailed() {
        when(collectionService.updateRecord(anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        NodeOutcome out = handler.execute(new NodeExecutionContext(
                instance,
                Map.of("id", "n1", "type", "DATA_UPDATE", "config", Map.of(
                        "collection", "orders", "recordId", "r-1",
                        "data", Map.of("status", "approved"))),
                null, null));

        assertThat(out).isEqualTo(NodeOutcome.FAILED);
    }
}
