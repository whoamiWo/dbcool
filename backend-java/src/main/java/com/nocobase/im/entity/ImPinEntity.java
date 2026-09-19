package com.nocobase.im.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 消息置顶。同一频道同一消息只能置顶一次(唯一约束)。
 */
@Entity
@Table(name = "im_pin")
public class ImPinEntity {

    public enum Status { PINNED, UNPINNED }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "pinned_by", nullable = false)
    private UUID pinnedBy;

    @Column(name = "pinned_at", nullable = false)
    private Instant pinnedAt = Instant.now();

    @Column(name = "unpinned_at")
    private Instant unpinnedAt;

    public ImPinEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }
    public UUID getPinnedBy() { return pinnedBy; }
    public void setPinnedBy(UUID pinnedBy) { this.pinnedBy = pinnedBy; }
    public Instant getPinnedAt() { return pinnedAt; }
    public void setPinnedAt(Instant pinnedAt) { this.pinnedAt = pinnedAt; }
    public Instant getUnpinnedAt() { return unpinnedAt; }
    public void setUnpinnedAt(Instant unpinnedAt) { this.unpinnedAt = unpinnedAt; }
}