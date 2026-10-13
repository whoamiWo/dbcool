package com.nocobase.integration.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * PHASE99 T1: 租户级集成凭证实体。
 *
 * <p>密文列 {@code appSecretEnc} 由 {@link CredentialCryptoService} 用 AES-GCM 加密，
 * 明文永不落库。主密钥来自环境变量，禁止硬编码。
 */
@Entity
@Table(name = "tenant_integration_credential")
public class TenantIntegrationCredentialEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** dingtalk / feishu / slack / wecom / mattermost */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    /** app-key / app-secret / client-id / signing-secret / bot-token / ... */
    @Column(name = "credential_key", nullable = false, length = 64)
    private String credentialKey;

    /** AES-GCM 密文（Base64(IV || ciphertext || tag)） */
    @Column(name = "app_secret_enc", nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String appSecretEnc;

    /** ACTIVE / DISABLED */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getCredentialKey() { return credentialKey; }
    public void setCredentialKey(String credentialKey) { this.credentialKey = credentialKey; }

    public String getAppSecretEnc() { return appSecretEnc; }
    public void setAppSecretEnc(String appSecretEnc) { this.appSecretEnc = appSecretEnc; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
