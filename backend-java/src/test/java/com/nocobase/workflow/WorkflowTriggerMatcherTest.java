package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.event.RecordChangeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WorkflowTriggerMatcher 单元测试(Week 41 D4a).
 */
class WorkflowTriggerMatcherTest {

    private WorkflowRepository repository;
    private WorkflowTriggerMatcher matcher;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRepository.class);
        objectMapper = new ObjectMapper();
        matcher = new WorkflowTriggerMatcher(repository, objectMapper);
    }

    private WorkflowEntity workflow(String name, String triggerJson, String collection, boolean enabled) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName(name);
        w.setTitle(name);
        w.setCollectionName(collection);
        w.setTenantId("tenant_default");
        w.setTriggerJson(triggerJson);
        w.setEnabled(enabled);
        w.setCreatedAt(Instant.now());
        return w;
    }

    private RecordChangeEvent event(RecordChangeEvent.ChangeType type, String collection) {
        return new RecordChangeEvent(type, collection, UUID.randomUUID().toString(),
                Map.of("foo", "bar"), "tenant_default", UUID.randomUUID());
    }

    // ============ mapEventToTriggerType ============

    @Test
    void mapEventToTriggerType_allTypes() {
        assertEquals("on_create", WorkflowTriggerMatcher.mapEventToTriggerType(RecordChangeEvent.ChangeType.CREATE));
        assertEquals("on_update", WorkflowTriggerMatcher.mapEventToTriggerType(RecordChangeEvent.ChangeType.UPDATE));
        assertEquals("on_delete", WorkflowTriggerMatcher.mapEventToTriggerType(RecordChangeEvent.ChangeType.DELETE));
    }

    // ============ matchesType ============

    @Test
    void matchesType_exactMatch_returnsTrue() {
        assertThat(matcher.matchesType("{\"type\":\"on_create\"}", "on_create")).isTrue();
    }

    @Test
    void matchesType_differentType_returnsFalse() {
        assertThat(matcher.matchesType("{\"type\":\"on_update\"}", "on_create")).isFalse();
    }

    @Test
    void matchesType_manualType_returnsFalseForEvents() {
        // manual 不响应事件(only API trigger)
        assertThat(matcher.matchesType("{\"type\":\"manual\"}", "on_create")).isFalse();
    }

    @Test
    void matchesType_invalidJson_returnsFalse() {
        // JSON 损坏不能 crash,应 false
        assertThat(matcher.matchesType("not json", "on_create")).isFalse();
        assertThat(matcher.matchesType("", "on_create")).isFalse();
    }

    // ============ findMatching ============

    @Test
    void findMatching_createEvent_returnsOnCreateWorkflows() {
        WorkflowEntity w1 = workflow("approval", "{\"type\":\"on_create\"}", "orders", true);
        WorkflowEntity w2 = workflow("notify", "{\"type\":\"on_create\"}", "orders", true);
        WorkflowEntity w3 = workflow("update_only", "{\"type\":\"on_update\"}", "orders", true);
        when(repository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(eq("orders"), eq("tenant_default")))
                .thenReturn(List.of(w1, w2, w3));

        List<WorkflowEntity> matches = matcher.findMatching(event(RecordChangeEvent.ChangeType.CREATE, "orders"));

        assertThat(matches).hasSize(2);
        assertThat(matches).extracting(WorkflowEntity::getName).containsExactlyInAnyOrder("approval", "notify");
    }

    @Test
    void findMatching_disabledWorkflow_excluded() {
        WorkflowEntity enabled = workflow("a", "{\"type\":\"on_create\"}", "orders", true);
        WorkflowEntity disabled = workflow("b", "{\"type\":\"on_create\"}", "orders", false);
        when(repository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(eq("orders"), eq("tenant_default")))
                .thenReturn(List.of(enabled, disabled));

        List<WorkflowEntity> matches = matcher.findMatching(event(RecordChangeEvent.ChangeType.CREATE, "orders"));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getName()).isEqualTo("a");
    }

    @Test
    void findMatching_differentCollection_excluded() {
        // workflow 是 "users" 的,event 是 "orders"
        WorkflowEntity w = workflow("a", "{\"type\":\"on_create\"}", "users", true);
        when(repository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(eq("orders"), eq("tenant_default")))
                .thenReturn(List.of());  // repo filter by collection
        when(repository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(eq("users"), eq("tenant_default")))
                .thenReturn(List.of(w));

        List<WorkflowEntity> matches = matcher.findMatching(event(RecordChangeEvent.ChangeType.CREATE, "orders"));

        assertThat(matches).isEmpty();
    }

    @Test
    void findMatching_deleteEvent_returnsOnDeleteWorkflows() {
        WorkflowEntity w = workflow("audit_delete", "{\"type\":\"on_delete\"}", "orders", true);
        when(repository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(eq("orders"), eq("tenant_default")))
                .thenReturn(List.of(w));

        List<WorkflowEntity> matches = matcher.findMatching(event(RecordChangeEvent.ChangeType.DELETE, "orders"));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getName()).isEqualTo("audit_delete");
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
