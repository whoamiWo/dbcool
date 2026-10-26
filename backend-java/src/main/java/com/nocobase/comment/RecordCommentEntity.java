package com.nocobase.comment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 记录级评论实体（对标 Notion/Airtable 行内讨论）。
 *
 * <p>设计对齐 {@code ImMessageEntity}：</p>
 * <ul>
 *   <li>{@code parentId} 实现线程回复</li>
 *   <li>{@code deletedAt} 软删除占位，避免线程断裂</li>
 *   <li>{@code mentions} JSON 懒解析，避免 JPA @Transient 陷阱</li>
 * </ul>
 */
@Entity
@Table(name = "record_comment")
public class RecordCommentEntity {

    @Id
    private UUID id;

    @Column(name = "collection_name", nullable = false, length = 255)
    private String collectionName;

    @Column(name = "record_id", nullable = false, length = 255)
    private String recordId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "content_type", nullable = false, length = 16)
    private String contentType = "text";

    /**
     * 提及列表 JSON（格式：[{displayName, userId}, ...]）。
     * 对外输出请调用 {@link #getMentionsParsed()}。
     */
    @Column(name = "mentions")
    private String mentions;

    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    // ==================== 懒解析提及 ====================

    private static final com.fasterxml.jackson.databind.ObjectMapper MENTION_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final java.util.logging.Logger MENTION_LOG =
            java.util.logging.Logger.getLogger(RecordCommentEntity.class.getName());

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
                .constructCollectionType(List.class, MentionDto.class));
        } catch (Exception e) {
            MENTION_LOG.warning("[comment] mentions JSON 解析失败，降级为空列表：" + e.getMessage());
            return List.of();
        }
    }

    public record MentionDto(String displayName, String userId) {}

    // ==================== Getter & Setter ====================

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public UUID getAuthorId() { return authorId; }
    public void setAuthorId(UUID authorId) { this.authorId = authorId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getMentions() { return mentions; }
    public void setMentions(String mentions) { this.mentions = mentions; }

    public UUID getAttachmentId() { return attachmentId; }
    public void setAttachmentId(UUID attachmentId) { this.attachmentId = attachmentId; }

    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
