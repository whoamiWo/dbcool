package com.nocobase.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * User-Role 多对多关联(Week 10 Epic 4 US-302).
 */
@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @EmbeddedId
    private UserRoleId id;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    public UserRoleEntity() {}

    public UserRoleEntity(UUID userId, UUID roleId) {
        this.id = new UserRoleId(userId, roleId);
        this.assignedAt = Instant.now();
    }

    public UserRoleId getId() { return id; }
    public void setId(UserRoleId id) { this.id = id; }

    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }

    @Embeddable
    public static class UserRoleId implements Serializable {
        @Column(name = "user_id")
        private UUID userId;

        @Column(name = "role_id")
        private UUID roleId;

        public UserRoleId() {}

        public UserRoleId(UUID userId, UUID roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        public UUID getUserId() { return userId; }
        public void setUserId(UUID userId) { this.userId = userId; }

        public UUID getRoleId() { return roleId; }
        public void setRoleId(UUID roleId) { this.roleId = roleId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UserRoleId other)) return false;
            return Objects.equals(userId, other.userId) && Objects.equals(roleId, other.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, roleId);
        }
    }
}
