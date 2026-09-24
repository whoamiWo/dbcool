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
 * AI Agent 定义(US-410 / Mattermost 差异化 2)。
 *
 * <p>一个 agent 是一个频道内的虚拟成员,接收 @ai / /ai 指令,
 * 通过 AgentToolRegistry 调用工具执行多步任务。
 */
@Entity
@Table(name = "ai_agent")
public class AiAgentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "channel_id")
    private UUID channelId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", length = 256)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tools", columnDefinition = "jsonb", nullable = false)
    private String allowedToolsJson = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum Status { ACTIVE, PAUSED }

    public AiAgentEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getAllowedToolsJson() { return allowedToolsJson; }
    public void setAllowedToolsJson(String allowedToolsJson) { this.allowedToolsJson = allowedToolsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}