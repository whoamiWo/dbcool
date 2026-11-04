package com.nocobase.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 抄送关系（E2）。 */
@Entity
@Table(name = "approval_cc")
@IdClass(ApprovalCcEntity.ApprovalCcId.class)
public class ApprovalCcEntity {

    @Id
    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ApprovalCcEntity() {}

    public ApprovalCcEntity(UUID requestId, UUID userId) {
        this.requestId = requestId;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class ApprovalCcId implements Serializable {
        private UUID requestId;
        private UUID userId;

        public ApprovalCcId() {}

        public ApprovalCcId(UUID requestId, UUID userId) {
            this.requestId = requestId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ApprovalCcId that)) return false;
            return Objects.equals(requestId, that.requestId) && Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(requestId, userId);
        }
    }
}
