package com.nocobase.im.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** IM 频道。type 取值 PUBLIC / PRIVATE / DIRECT;DIRECT 用 directKey 去重。 */
@Entity
@Table(name = "im_channel")
public class ImChannelEntity {

    public enum Type { PUBLIC, PRIVATE, DIRECT }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "type", nullable = false, length = 16)
    private String type = Type.PUBLIC.name();

    @Column(name = "topic", length = 512)
    private String topic;

    /** 直聊去重键 '&lt;小uuid&gt;:&lt;大uuid&gt;';非 DIRECT 频道为 null。 */
    @Column(name = "direct_key", length = 129)
    private String directKey;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getDirectKey() { return directKey; }
    public void setDirectKey(String directKey) { this.directKey = directKey; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
