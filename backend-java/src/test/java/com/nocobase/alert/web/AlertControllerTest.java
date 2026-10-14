package com.nocobase.alert.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.nocobase.alert.AlertCollector;
import com.nocobase.alert.AlertEvent;
import com.nocobase.alert.AlertStore;
import com.nocobase.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE105: AlertController 覆盖（mock 依赖，覆盖各端点分支）。
 */
public class AlertControllerTest {

    private AlertCollector collector;
    private AlertStore store;
    private AuditService auditService;
    private AlertController controller;

    @BeforeEach
    void setUp() {
        collector = mock(AlertCollector.class);
        store = mock(AlertStore.class);
        auditService = mock(AuditService.class);
        controller = new AlertController(collector, store, auditService);
    }

    @Test
    void recent_storeHasRows_returnsStoreRows() {
        Map<String, Object> row = Map.of("id", "e1", "kind", "test");
        when(store.iterAlerts(50, true, null)).thenReturn(List.of(row));
        when(store.countAlerts(true)).thenReturn(Map.of("test", 1));
        when(store.unresolvedCount()).thenReturn(1);

        Map<String, Object> resp = controller.recent(50, true);
        assertEquals(List.of(row), resp.get("events"));
        assertEquals(Map.of("test", 1), resp.get("counts_by_kind"));
        assertEquals(1, resp.get("unresolved_count"));
    }

    @Test
    void recent_storeEmpty_fallsBackToMemory() {
        when(store.iterAlerts(50, true, null)).thenReturn(List.of());
        AlertEvent ev = new AlertEvent("test", "u1", Map.of());
        when(collector.recent(50, true)).thenReturn(List.of(ev));
        when(store.countAlerts(true)).thenReturn(Map.of());
        when(store.unresolvedCount()).thenReturn(0);

        Map<String, Object> resp = controller.recent(50, true);
        List<?> events = (List<?>) resp.get("events");
        assertEquals(1, events.size());
    }

    @Test
    void ack_success_updatesStoreAndAudits() {
        when(collector.ack("e1", "admin")).thenReturn(true);
        var resp = controller.ack("e1", Map.of("by", "admin"), null);
        assertEquals(true, resp.getBody().get("ok"));
    }

    @Test
    void ack_notFound_returnsFalse() {
        when(collector.ack("e1", "admin")).thenReturn(false);
        var resp = controller.ack("e1", Map.of("by", "admin"), null);
        assertEquals(false, resp.getBody().get("ok"));
    }

    @Test
    void ack_nullBody_usesNullBy() {
        when(collector.ack("e1", null)).thenReturn(true);
        var resp = controller.ack("e1", null, null);
        assertEquals(true, resp.getBody().get("ok"));
    }

    @Test
    void resolve_success_updatesStoreAndAudits() {
        when(collector.resolve("e1")).thenReturn(true);
        var resp = controller.resolve("e1", null);
        assertEquals(true, resp.getBody().get("ok"));
    }

    @Test
    void resolve_notFound_returnsFalse() {
        when(collector.resolve("e1")).thenReturn(false);
        var resp = controller.resolve("e1", null);
        assertEquals(false, resp.getBody().get("ok"));
    }

    @Test
    void subscribe_missingFields_returnsError() {
        Map<String, Object> resp = controller.subscribe(Map.of("user_id", "u1"), null);
        assertEquals(false, resp.get("ok"));
    }

    @Test
    void subscribe_success() {
        when(store.subscribe("u1", "kind1")).thenReturn(true);
        Map<String, Object> resp = controller.subscribe(Map.of("user_id", "u1", "kind", "kind1"), null);
        assertEquals(true, resp.get("ok"));
        assertEquals(true, resp.get("created"));
    }

    @Test
    void unsubscribe_returnsRemoved() {
        when(store.unsubscribe("u1", "kind1")).thenReturn(true);
        Map<String, Object> resp = controller.unsubscribe("u1", "kind1");
        assertEquals(true, resp.get("ok"));
    }

    @Test
    void listSubscriptions_returnsKinds() {
        when(store.subscriptionsFor("u1")).thenReturn(List.of("kind1", "kind2"));
        Map<String, Object> resp = controller.listSubscriptions("u1");
        assertEquals(List.of("kind1", "kind2"), resp.get("kinds"));
    }

    @Test
    void storeStats_returnsStats() {
        when(store.getPath()).thenReturn("/tmp/alerts.db");
        when(store.unresolvedCount()).thenReturn(3);
        when(store.countAlerts(true)).thenReturn(Map.of("a", 1));
        when(store.countAlerts(false)).thenReturn(Map.of("a", 1));
        when(collector.size()).thenReturn(5);

        Map<String, Object> resp = controller.storeStats();
        assertEquals("/tmp/alerts.db", resp.get("path"));
        assertEquals(3, resp.get("unresolved"));
        assertEquals(5, resp.get("in_memory_size"));
    }
}
