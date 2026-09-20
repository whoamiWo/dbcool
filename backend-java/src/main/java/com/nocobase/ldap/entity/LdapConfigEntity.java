package com.nocobase.ldap.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * LDAP 配置实体。
 */
@Entity
@Table(name = "ldap_config")
public class LdapConfigEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    @Column(name = "server_url", nullable = false, length = 512)
    private String serverUrl;

    @Column(name = "base_dn", nullable = false, length = 512)
    private String baseDn;

    @Column(name = "bind_dn", length = 512)
    private String bindDn;

    @Column(name = "bind_password", length = 512)
    private String bindPassword;

    @Column(name = "user_search_filter", nullable = false, length = 512)
    private String userSearchFilter = "(objectClass=person)";

    @Column(name = "group_search_filter", nullable = false, length = 512)
    private String groupSearchFilter = "(objectClass=group)";

    @Column(name = "attribute_mapping", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String attributeMappingJson = "{}";

    @Column(name = "sync_interval_minutes")
    private Integer syncIntervalMinutes = 60;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_sync_status", length = 20)
    private String lastSyncStatus;

    @Column(name = "last_sync_error", columnDefinition = "TEXT")
    private String lastSyncError;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getBaseDn() { return baseDn; }
    public void setBaseDn(String baseDn) { this.baseDn = baseDn; }

    public String getBindDn() { return bindDn; }
    public void setBindDn(String bindDn) { this.bindDn = bindDn; }

    public String getBindPassword() { return bindPassword; }
    public void setBindPassword(String bindPassword) { this.bindPassword = bindPassword; }

    public String getUserSearchFilter() { return userSearchFilter; }
    public void setUserSearchFilter(String userSearchFilter) { this.userSearchFilter = userSearchFilter; }

    public String getGroupSearchFilter() { return groupSearchFilter; }
    public void setGroupSearchFilter(String groupSearchFilter) { this.groupSearchFilter = groupSearchFilter; }

    public String getAttributeMappingJson() { return attributeMappingJson; }
    public void setAttributeMappingJson(String attributeMappingJson) { this.attributeMappingJson = attributeMappingJson; }

    public Integer getSyncIntervalMinutes() { return syncIntervalMinutes; }
    public void setSyncIntervalMinutes(Integer syncIntervalMinutes) { this.syncIntervalMinutes = syncIntervalMinutes; }

    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public String getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(String lastSyncStatus) { this.lastSyncStatus = lastSyncStatus; }

    public String getLastSyncError() { return lastSyncError; }
    public void setLastSyncError(String lastSyncError) { this.lastSyncError = lastSyncError; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
