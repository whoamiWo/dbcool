package com.nocobase.alert.web;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nocobase.alert.AlertCollector;
import com.nocobase.alert.AlertEvent;
import com.nocobase.alert.AlertStore;

/**
 * R11: 告警 REST API(对等 Python /api/ai/alerts/...).
 *
 * - GET    /api/alerts/recent
 * - POST   /api/alerts/{id}/ack
 * - POST   /api/alerts/{id}/resolve
 * - POST   /api/alerts/subscriptions
 * - DELETE /api/alerts/subscriptions
 * - GET    /api/alerts/subscriptions/{userId}
 * - GET    /api/alerts/store/stats
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertCollector collector;
    private final AlertStore store;
    private final AuditService auditService;

    @Autowired
    public AlertController(AlertCollector collector, AlertStore store, AuditService auditService) {
        this.collector = collector;
        this.store = store;
        this.auditService = auditService;
    }

    @GetMapping("/recent")
    public Map<String, Object> recent(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "true") boolean includeResolved
    ) {
        Map<String, Object> resp = new HashMap<>();
        // 优先用 store(支持跨进程持久化);若 store 为空则用内存 collector
        var rows = store.iterAlerts(limit, includeResolved, null);
        if (rows.isEmpty()) {
            // 回退到内存
            List<Map<String, Object>> evs = new java.util.ArrayList<>();
            for (AlertEvent ev : collector.recent(limit, includeResolved)) {
                evs.add(ev.toMap());
            }
            resp.put("events", evs);
        } else {
            resp.put("events", rows);
        }
        resp.put("counts_by_kind", store.countAlerts(true));
        resp.put("unresolved_count", store.unresolvedCount());
        return resp;
    }

    @PostMapping("/{eventId}/ack")
    public ResponseEntity<Map<String, Object>> ack(@PathVariable String eventId,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        String by = body != null ? body.get("by") : null;
        boolean ok = collector.ack(eventId, by);
        if (ok) {
            store.updateAlert(eventId, true, by, System.currentTimeMillis() / 1000.0, null, null);
        }
        String tenantId = principal != null ? principal.tenantId() : "unknown";
        Object userId = principal != null ? principal.userId() : "system";
        String username = principal != null ? principal.username() : "system";
        auditService.log(tenantId, userId, username,
                "alert.ack", "alert", eventId,
                Map.of("alertId", eventId, "ackedBy", by != null ? by : username));
        return ResponseEntity.ok(Map.of("ok", ok, "event_id", eventId));
    }

    @PostMapping("/{eventId}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String eventId, @AuthenticationPrincipal AuthenticatedUser principal) {
        boolean ok = collector.resolve(eventId);
        if (ok) {
            store.updateAlert(eventId, null, null, null, true, System.currentTimeMillis() / 1000.0);
        }
        String tenantId = principal != null ? principal.tenantId() : "unknown";
        Object userId = principal != null ? principal.userId() : "system";
        String username = principal != null ? principal.username() : "system";
        auditService.log(tenantId, userId, username,
                "alert.resolve", "alert", eventId,
                Map.of("alertId", eventId));
        return ResponseEntity.ok(Map.of("ok", ok, "event_id", eventId));
    }

    @PostMapping("/subscriptions")
    public Map<String, Object> subscribe(@RequestBody Map<String, String> body,
                                         @AuthenticationPrincipal AuthenticatedUser principal) {
        String userId = body.get("user_id");
        String kind = body.get("kind");
        if (userId == null || kind == null) {
            return Map.of("ok", false, "error", "user_id 和 kind 必填");
        }
        boolean created = store.subscribe(userId, kind);
        String tenantId = principal != null ? principal.tenantId() : "unknown";
        auditService.log(tenantId, userId, userId,
                "alert.subscribe", "alert_subscription", userId + "_" + kind,
                Map.of("userId", userId, "kind", kind));
        return Map.of("ok", true, "created", created, "user_id", userId, "kind", kind);
    }

    @DeleteMapping("/subscriptions")
    public Map<String, Object> unsubscribe(
            @RequestParam String user_id,
            @RequestParam String kind
    ) {
        boolean removed = store.unsubscribe(user_id, kind);
        return Map.of("ok", removed, "user_id", user_id, "kind", kind);
    }

    @GetMapping("/subscriptions/{userId}")
    public Map<String, Object> listSubscriptions(@PathVariable String userId) {
        return Map.of("user_id", userId, "kinds", store.subscriptionsFor(userId));
    }

    @GetMapping("/store/stats")
    public Map<String, Object> storeStats() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("path", store.getPath());
        resp.put("max_alerts", 10000);  // 默认值
        resp.put("unresolved", store.unresolvedCount());
        resp.put("by_kind", store.countAlerts(true));
        resp.put("by_kind_unresolved", store.countAlerts(false));
        resp.put("in_memory_size", collector.size());
        return resp;
    }
}
