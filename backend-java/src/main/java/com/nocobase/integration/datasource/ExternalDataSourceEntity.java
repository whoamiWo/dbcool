package com.nocobase.integration.datasource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * PHASE B B3: 外部数据库连接配置实体（MySQL / PostgreSQL）。
 *
 * <p>密码列 {@code password_enc} 由 {@code CredentialCryptoService} 用 AES-GCM
 * 加密，明文永不落库。
 *
 * <p>唯一键 {@code (tenant_id, name)} 保证同一租户下数据源名不重复，
 * 与 {@code tenant_integration_credential} 的命名隔离范式一致。
 */
@Entity
@Table(
        name = "external_data_source",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_external_data_source_tenant_name",
                columnNames = {"tenant_id", "name"}))
public class ExternalDataSourceEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** vendor: mysql / postgresql（驱动反射加载；mysql 驱动为可选 runtime 依赖）。 */
    @Column(name = "vendor", nullable = false, length = 32)
    private String vendor;

    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "database_name", nullable = false, length = 255)
    private String databaseName;

    @Column(name = "username", nullable = false, length = 128)
    private String username;

    /**
     * 显式驱动类名（可选）。
     * <p>非空时优先使用此驱动类，跳过 vendor → 默认驱动的推断，
     * 让测试可注入任意驱动类，也支持自定义 JDBC 驱动的第三方库。
     */
    @Column(name = "driver_class_name", length = 255)
    private String driverClassName;

    @Column(name = "password_enc", nullable = false, columnDefinition = "TEXT")
    private String passwordEnc;

    /** ACTIVE / DISABLED。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

    public String getPasswordEnc() { return passwordEnc; }
    public void setPasswordEnc(String passwordEnc) { this.passwordEnc = passwordEnc; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
