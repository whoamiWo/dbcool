package com.nocobase.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 站内信(US-505).
 */
@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "sender")
    private UUID sender;  // NULL 表示系统消息

    @Column(name = "recipient", nullable = false)
    private UUID recipient;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body")
    private String body;

    @Column(name = "related_id", length = 64)
    private String relatedId;

    @Column(name = "read", nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    // ---------- V79 通知中心（E3） ----------

    /** 点击跳转路径（如 /approvals/{id}）。 */
    @Column(name = "deeplink", length = 512)
    private String deeplink;

    @Column(name = "read_at")
    private Instant readAt;

    /** 聚合键：同 key 10 分钟内合并为一条。 */
    @Column(name = "digest_key", length = 128)
    private String digestKey;

    /** LOW / NORMAL / HIGH。 */
    @Column(name = "priority", nullable = false, length = 16)
    private String priority = "NORMAL";

    /** 外部渠道投递结果 JSON。 */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "channel_sent", columnDefinition = "jsonb", nullable = false)
    private String channelSentJson = "{}";

    @Column(name = "digest_count", nullable = false)
    private int digestCount = 1;

    public MessageEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSender() { return sender; }
    public void setSender(UUID sender) { this.sender = sender; }
    public UUID getRecipient() { return recipient; }
    public void setRecipient(UUID recipient) { this.recipient = recipient; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getRelatedId() { return relatedId; }
    public void setRelatedId(String relatedId) { this.relatedId = relatedId; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDeeplink() { return deeplink; }
    public void setDeeplink(String deeplink) { this.deeplink = deeplink; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public String getDigestKey() { return digestKey; }
    public void setDigestKey(String digestKey) { this.digestKey = digestKey; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getChannelSentJson() { return channelSentJson; }
    public void setChannelSentJson(String channelSentJson) { this.channelSentJson = channelSentJson; }
    public int getDigestCount() { return digestCount; }
    public void setDigestCount(int digestCount) { this.digestCount = digestCount; }
}
