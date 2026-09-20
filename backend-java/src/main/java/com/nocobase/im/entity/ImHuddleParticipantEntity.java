package com.nocobase.im.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Huddle 参与者实体。
 */
@Entity
@Table(name = "im_huddle_participant")
public class ImHuddleParticipantEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "huddle_id", nullable = false)
    private UUID huddleId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "is_muted", nullable = false)
    private Boolean isMuted = false;

    @Column(name = "is_screen_sharing", nullable = false)
    private Boolean isScreenSharing = false;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getHuddleId() { return huddleId; }
    public void setHuddleId(UUID huddleId) { this.huddleId = huddleId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }

    public Instant getLeftAt() { return leftAt; }
    public void setLeftAt(Instant leftAt) { this.leftAt = leftAt; }

    public Boolean getIsMuted() { return isMuted; }
    public void setIsMuted(Boolean isMuted) { this.isMuted = isMuted; }

    public Boolean getIsScreenSharing() { return isScreenSharing; }
    public void setIsScreenSharing(Boolean isScreenSharing) { this.isScreenSharing = isScreenSharing; }
}
