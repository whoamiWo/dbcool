package com.nocobase.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI 会话上下文(US-410 / Mattermost 差异化 2)。
 *
 * <p>存储多轮对话消息(JSONB),支持频道内 @ai 触发的上下文记忆。
 */
@Entity
@Table(name = "ai_conversation")
public class AiConversationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "channel_id")
    private UUID channelId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "messages", columnDefinition = "jsonb", nullable = false)
    private String messagesJson = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AiConversationEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }
    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getMessagesJson() { return messagesJson; }
    public void setMessagesJson(String messagesJson) { this.messagesJson = messagesJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}