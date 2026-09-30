package com.nocobase.integration.market;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 租户级集成安装记录。
 */
@Entity
@Table(name = "tenant_integration_install")
public class TenantIntegrationInstallEntity {

    public enum InstallStatus { INSTALLED, DISABLED, UNINSTALLING }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "app_key", nullable = false, length = 128)
    private String appKey;

    @Column(name = "app_name", nullable = false, length = 256)
    private String appName;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "INSTALLED";

    @Column(name = "config_json", columnDefinition = "jsonb")
    private String configJson = "{}";

    @Column(name = "installed_by")
    private UUID installedBy;

    @Column(name = "installed_at", nullable = false)
    private Instant installedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected TenantIntegrationInstallEntity() {
    }

    public TenantIntegrationInstallEntity(String tenantId, String appKey, String appName, String version) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.appKey = appKey;
        this.appName = appName;
        this.version = version;
        this.status = "INSTALLED";
        this.configJson = "{}";
        this.installedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public UUID getInstalledBy() { return installedBy; }
    public void setInstalledBy(UUID installedBy) { this.installedBy = installedBy; }

    public Instant getInstalledAt() { return installedAt; }
    public void setInstalledAt(Instant installedAt) { this.installedAt = installedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
