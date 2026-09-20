package com.nocobase.plugin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 插件市场应用注册实体。
 *
 * <p>存储应用商店中的第三方应用/插件信息，支持安装、升级、卸载。
 */
@Entity
@Table(name = "plugin_marketplace_app")
public class PluginMarketplaceAppEntity {

    public enum Status { AVAILABLE, INSTALLED, PENDING_INSTALL, INSTALL_FAILED, UPDATING }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "app_key", nullable = false, unique = true, length = 128)
    private String appKey;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @Column(name = "latest_version", length = 32)
    private String latestVersion;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "author", length = 128)
    private String author;

    @Column(name = "icon_url", length = 512)
    private String iconUrl;

    @Column(name = "homepage_url", length = 512)
    private String homepageUrl;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "tags_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String tagsJson = "[]";

    @Column(name = "permissions_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String permissionsJson = "[]";

    @Column(name = "manifest_yaml", columnDefinition = "TEXT")
    private String manifestYaml;

    @Column(name = "config_schema_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String configSchemaJson = "{}";

    @Column(name = "status", nullable = false, length = 20)
    private String status = "AVAILABLE";

    @Column(name = "install_error", columnDefinition = "TEXT")
    private String installError;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "config_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String configJson = "{}";

    @Column(name = "installed_by")
    private UUID installedBy;

    @Column(name = "installed_at")
    private Instant installedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getLatestVersion() { return latestVersion; }
    public void setLatestVersion(String latestVersion) { this.latestVersion = latestVersion; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }

    public String getHomepageUrl() { return homepageUrl; }
    public void setHomepageUrl(String homepageUrl) { this.homepageUrl = homepageUrl; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }

    public String getPermissionsJson() { return permissionsJson; }
    public void setPermissionsJson(String permissionsJson) { this.permissionsJson = permissionsJson; }

    public String getManifestYaml() { return manifestYaml; }
    public void setManifestYaml(String manifestYaml) { this.manifestYaml = manifestYaml; }

    public String getConfigSchemaJson() { return configSchemaJson; }
    public void setConfigSchemaJson(String configSchemaJson) { this.configSchemaJson = configSchemaJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInstallError() { return installError; }
    public void setInstallError(String installError) { this.installError = installError; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public UUID getInstalledBy() { return installedBy; }
    public void setInstalledBy(UUID installedBy) { this.installedBy = installedBy; }

    public Instant getInstalledAt() { return installedAt; }
    public void setInstalledAt(Instant installedAt) { this.installedAt = installedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
