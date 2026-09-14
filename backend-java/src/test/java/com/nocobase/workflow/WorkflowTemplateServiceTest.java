package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.meta.CollectionService;
import com.nocobase.meta.FieldDef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * WorkflowTemplateService 单测(Week 29).
 * 覆盖 install() — collection 创建/跳过 + workflow 创建.
 */
class WorkflowTemplateServiceTest {

    private WorkflowTemplateRegistry registry;
    private CollectionService collectionService;
    private WorkflowRepository workflowRepository;
    private WorkflowTemplateService service;

    @BeforeEach
    void setUp() {
        registry = mock(WorkflowTemplateRegistry.class);
        collectionService = mock(CollectionService.class);
        workflowRepository = mock(WorkflowRepository.class);
        service = new WorkflowTemplateService(registry, collectionService, workflowRepository);

        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private WorkflowTemplate template() {
        return new WorkflowTemplate(
                "test_key", "Test Template", "ops", "desc", null,
                List.of(new WorkflowTemplate.TemplateCollection(
                        "tasks", "Tasks", "todo items",
                        List.of(Map.of("name", "title", "type", "text", "label", "Title")))),
                new WorkflowTemplate.TemplateWorkflow(
                        "task_flow", "Task workflow", "tasks",
                        Map.of("type", "manual"),
                        List.of(Map.of("id", "n1", "type", "APPROVAL")),
                        List.of()));
    }

    @Test
    void install_unknownTemplate_returns404() {
        when(registry.get("missing")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.install("tenant_default", "missing", UUID.randomUUID()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void install_newCollection_createsCollectionAndWorkflow() {
        WorkflowTemplate tpl = template();
        when(registry.get("test_key")).thenReturn(Optional.of(tpl));
        when(collectionService.get("tasks")).thenThrow(new RuntimeException("not exists"));

        Map<String, Object> resp = service.install("tenant_default", "test_key", UUID.randomUUID());

        assertEquals("test_key", resp.get("template_key"));
        @SuppressWarnings("unchecked")
        List<String> created = (List<String>) resp.get("created_collections");
        assertEquals(1, created.size());
        assertEquals("tasks", created.get(0));
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) resp.get("skipped_collections");
        assertEquals(0, skipped.size());
        assertNotNullUUID(resp.get("workflow_id"));
        verify(collectionService, times(1)).create(eq("tasks"), anyString(), any(),
                any(), eq("tenant_default"), any());
    }

    @Test
    void install_existingCollection_skipsCreation() {
        WorkflowTemplate tpl = template();
        when(registry.get("test_key")).thenReturn(Optional.of(tpl));
        // collectionService.get() 不抛 → 已存在
        when(collectionService.get("tasks")).thenReturn(new com.nocobase.meta.CollectionMetaEntity());

        Map<String, Object> resp = service.install("tenant_default", "test_key", UUID.randomUUID());

        @SuppressWarnings("unchecked")
        List<String> created = (List<String>) resp.get("created_collections");
        assertEquals(0, created.size());
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) resp.get("skipped_collections");
        assertEquals(1, skipped.size());
        assertEquals("tasks", skipped.get(0));
        verify(collectionService, times(0)).create(anyString(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void install_emptyCollections_justCreatesWorkflow() {
        WorkflowTemplate tpl = new WorkflowTemplate(
                "k", "n", "c", "d", null,
                List.of(),
                new WorkflowTemplate.TemplateWorkflow("wf", "d", "posts",
                        Map.of("type", "manual"), List.of(), List.of()));
        when(registry.get("k")).thenReturn(Optional.of(tpl));

        Map<String, Object> resp = service.install("tenant_default", "k", UUID.randomUUID());

        @SuppressWarnings("unchecked")
        List<String> created = (List<String>) resp.get("created_collections");
        assertEquals(0, created.size());
        assertNotNullUUID(resp.get("workflow_id"));
        assertEquals("wf", resp.get("workflow_name"));
    }

    @Test
    void install_multipleCollections_partiallyCreated() {
        WorkflowTemplate.TemplateCollection c1 = new WorkflowTemplate.TemplateCollection(
                "a", "A", "d", List.of());
        WorkflowTemplate.TemplateCollection c2 = new WorkflowTemplate.TemplateCollection(
                "b", "B", "d", List.of());
        WorkflowTemplate tpl = new WorkflowTemplate(
                "k", "n", "c", "d", null, List.of(c1, c2),
                new WorkflowTemplate.TemplateWorkflow("wf", "d", "a",
                        Map.of("type", "manual"), List.of(), List.of()));
        when(registry.get("k")).thenReturn(Optional.of(tpl));
        // a 不存在,b 已存在
        when(collectionService.get("a")).thenThrow(new RuntimeException("no"));
        when(collectionService.get("b")).thenReturn(new com.nocobase.meta.CollectionMetaEntity());

        Map<String, Object> resp = service.install("tenant_default", "k", UUID.randomUUID());

        @SuppressWarnings("unchecked")
        List<String> created = (List<String>) resp.get("created_collections");
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) resp.get("skipped_collections");
        assertEquals(1, created.size());
        assertEquals("a", created.get(0));
        assertEquals(1, skipped.size());
        assertEquals("b", skipped.get(0));
    }

    private void assertNotNullUUID(Object o) {
        assertTrue(o != null && o.toString().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }
}
