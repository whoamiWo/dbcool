package com.nocobase.alert;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * R11: 告警事件(Java 端). 与 Python 端 AlertEvent 对齐.
 */
public final class AlertEvent {

    private final String id;
    private final String kind;
    private final String userId;
    private final Map<String, Object> detail;
    private final double timestamp;
    private boolean acked;
    private String ackedBy;
    private Double ackedAt;
    private boolean resolved;
    private Double resolvedAt;

    public AlertEvent(String kind, String userId, Map<String, Object> detail) {
        this.id = UUID.randomUUID().toString().substring(0, 12);
        this.kind = kind;
        this.userId = userId;
        this.detail = detail == null ? new HashMap<>() : new HashMap<>(detail);
        this.timestamp = System.currentTimeMillis() / 1000.0;
    }

    public String id() { return id; }
    public String kind() { return kind; }
    public String userId() { return userId; }
    public Map<String, Object> detail() { return detail; }
    public double timestamp() { return timestamp; }
    public boolean acked() { return acked; }
    public String ackedBy() { return ackedBy; }
    public Double ackedAt() { return ackedAt; }
    public boolean resolved() { return resolved; }
    public Double resolvedAt() { return resolvedAt; }

    public void ack(String by) {
        this.acked = true;
        this.ackedBy = by;
        this.ackedAt = System.currentTimeMillis() / 1000.0;
    }

    public void resolve() {
        this.resolved = true;
        this.resolvedAt = System.currentTimeMillis() / 1000.0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("user_id", userId);
        m.put("detail", detail);
        m.put("timestamp", timestamp);
        m.put("acked", acked);
        m.put("acked_by", ackedBy);
        m.put("acked_at", ackedAt);
        m.put("resolved", resolved);
        m.put("resolved_at", resolvedAt);
        return m;
    }
}
