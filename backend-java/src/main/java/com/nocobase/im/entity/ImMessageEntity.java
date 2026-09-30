package com.nocobase.im.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 消息。parentId 非空表示线程回复;删除为软删除 (deletedAt),
 * 以保留线程上下文——父消息被删时子回复仍可展示"回复了已删除消息"。
 */
@Entity
@Table(name = "im_message")
public class ImMessageEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "content_type", nullable = false, length = 16)
    private String contentType = "text";

    @Column(name = "mentions")
    private String mentions;

    // 注: 不要在此处缓存解析结果(@Transient 字段) —— JPA 不会填充它,
    // 从库里读出的实体该字段恒为 null。解析一律走 getMentionsParsed() 懒解析。

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "burn_after_read", nullable = false)
    private Boolean burnAfterRead = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getMentions() { return mentions; }
    public void setMentions(String mentions) { this.mentions = mentions; }

    private static final com.fasterxml.jackson.databind.ObjectMapper MENTION_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final java.util.logging.Logger MENTION_LOG =
            java.util.logging.Logger.getLogger(ImMessageEntity.class.getName());

    /**
     * 解析落库的 mentions JSON 为结构化列表（供 DTO 序列化给前端）。
     *
     * <p>必须**懒解析**：JPA 不会填充 {@code @Transient} 字段，从数据库读出的实体
     * 若只返回字段值则恒为 null → 列表/搜索接口返回的 mentions 为空、前端不渲染。
     *
     * <p>解析失败时降级为空列表并打 WARN —— 此前静默返回空，JSON 损坏时无从排查。
     */
    @JsonProperty("mentions")
    public List<MentionDto> getMentionsParsed() {
        if (mentions == null || mentions.isEmpty()) return List.of();
        try {
            return MENTION_MAPPER.readValue(mentions, MENTION_MAPPER.getTypeFactory()
                .constructCollectionType(java.util.List.class, MentionDto.class));
        } catch (Exception e) {
            MENTION_LOG.warning("[im] mentions JSON 解析失败，降级为空列表: " + e.getMessage());
            return List.of();
        }
    }

    public record MentionDto(String displayName, String userId) {}
    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Boolean getBurnAfterRead() { return burnAfterRead; }
    public void setBurnAfterRead(Boolean burnAfterRead) { this.burnAfterRead = burnAfterRead; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
