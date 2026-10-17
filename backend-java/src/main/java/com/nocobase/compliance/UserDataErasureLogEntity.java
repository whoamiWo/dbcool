package com.nocobase.compliance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户数据删除请求台账（V52 建的表，PHASE112 前全仓零引用）。
 *
 * <p>用途：合规审计要求可查询「删除请求台账」—— 谁在什么时间对哪个用户发起删除、
 * 处理结果如何（IM / AI / 审计三类计数）、以及对应的匿名标识。
 *
 * <p>与 {@code audit_log} 的区别：
 * <ul>
 *   <li>{@code audit_log} 是「操作留痕」，字段宽（含 ip、user_agent、resource…）；</li>
 *   <li>{@code user_data_erasure_log} 是「删除请求台账」，字段窄、计数精确，
 *       面向合规审计直接检索（例如 "过去 90 天某租户发起过几次 GDPR 删除"）。</li>
 * </ul>
 *
 * <p>本实体仅由 {@code UserDataErasureService} 在删除请求成功路径写入，
 * 不开放独立 Repository/Controller —— 对外查询仍走既有的审计日志接口
 * （{@code GET /api/audit/logs?action=user.data.erasure}）。
 */
@Entity
@Table(name = "user_data_erasure_log")
public class UserDataErasureLogEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "requester_id", length = 64)
    private String requesterId;

    @Column(name = "anonymous_id", length = 64)
    private String anonymousId;

    @Column(name = "messages_erased")
    private int messagesErased;

    @Column(name = "conversations_erased")
    private int conversationsErased;

    @Column(name = "audit_logs_anonymized")
    private int auditLogsAnonymized;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (requestedAt == null) {
            requestedAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String requesterId) { this.requesterId = requesterId; }
    public String getAnonymousId() { return anonymousId; }
    public void setAnonymousId(String anonymousId) { this.anonymousId = anonymousId; }
    public int getMessagesErased() { return messagesErased; }
    public void setMessagesErased(int messagesErased) { this.messagesErased = messagesErased; }
    public int getConversationsErased() { return conversationsErased; }
    public void setConversationsErased(int conversationsErased) { this.conversationsErased = conversationsErased; }
    public int getAuditLogsAnonymized() { return auditLogsAnonymized; }
    public void setAuditLogsAnonymized(int auditLogsAnonymized) { this.auditLogsAnonymized = auditLogsAnonymized; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
}