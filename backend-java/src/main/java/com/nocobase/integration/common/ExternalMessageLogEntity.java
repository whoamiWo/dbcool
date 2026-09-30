package com.nocobase.integration.common;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 第三方入站消息去重日志。
 *
 * <p>记录已处理的第三方消息 ID，用于幂等去重和防环。
 */
@Entity
@Table(name = "integration_external_message_log")
public class ExternalMessageLogEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "external_message_id", nullable = false, length = 256)
    private String externalMessageId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ExternalMessageLogEntity() {
    }

    public ExternalMessageLogEntity(String source, String externalMessageId, String tenantId) {
        this.id = UUID.randomUUID();
        this.source = source;
        this.externalMessageId = externalMessageId;
        this.tenantId = tenantId;
        this.processedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getExternalMessageId() { return externalMessageId; }
    public void setExternalMessageId(String externalMessageId) { this.externalMessageId = externalMessageId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
