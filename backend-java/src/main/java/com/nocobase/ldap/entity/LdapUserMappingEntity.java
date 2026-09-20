package com.nocobase.ldap.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * LDAP 用户映射实体。
 */
@Entity
@Table(name = "ldap_user_mapping")
public class LdapUserMappingEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "ldap_dn", nullable = false, length = 512)
    private String ldapDn;

    @Column(name = "ldap_uid", nullable = false, length = 256)
    private String ldapUid;

    @Column(name = "local_user_id")
    private UUID localUserId;

    @Column(name = "sync_status", nullable = false, length = 20)
    private String syncStatus = "PENDING";

    @Column(name = "sync_error", columnDefinition = "TEXT")
    private String syncError;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "ldap_attributes", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String ldapAttributesJson = "{}";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getLdapDn() { return ldapDn; }
    public void setLdapDn(String ldapDn) { this.ldapDn = ldapDn; }

    public String getLdapUid() { return ldapUid; }
    public void setLdapUid(String ldapUid) { this.ldapUid = ldapUid; }

    public UUID getLocalUserId() { return localUserId; }
    public void setLocalUserId(UUID localUserId) { this.localUserId = localUserId; }

    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }

    public String getSyncError() { return syncError; }
    public void setSyncError(String syncError) { this.syncError = syncError; }

    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public String getLdapAttributesJson() { return ldapAttributesJson; }
    public void setLdapAttributesJson(String ldapAttributesJson) { this.ldapAttributesJson = ldapAttributesJson; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
