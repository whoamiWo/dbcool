package com.nocobase.userdata;

import java.time.Instant;

/**
 * 用户数据删除请求
 */
public class UserDataErasureRequest {
    private String userId;
    private String requester;
    private Instant requestedAt;
    private String tenantId;
    private boolean success = false;
    private int messagesErased = 0;
    private int conversationsErased = 0;
    private int auditLogsAnonymized = 0;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getRequester() { return requester; }
    public void setRequester(String requester) { this.requester = requester; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public int getMessagesErased() { return messagesErased; }
    public void setMessagesErased(int messagesErased) { this.messagesErased = messagesErased; }

    public int getConversationsErased() { return conversationsErased; }
    public void setConversationsErased(int conversationsErased) { this.conversationsErased = conversationsErased; }

    public int getAuditLogsAnonymized() { return auditLogsAnonymized; }
    public void setAuditLogsAnonymized(int auditLogsAnonymized) { this.auditLogsAnonymized = auditLogsAnonymized; }

    public void incrementMessagesErased(int count) { this.messagesErased += count; }
    public void incrementConversationsErased(int count) { this.conversationsErased += count; }
    public void incrementAuditLogsAnonymized(int count) { this.auditLogsAnonymized += count; }
}