package com.nocobase.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** (corpId, processInstanceId) 到本地单据的唯一、不可猜测绑定。 */
@Entity
@Table(name = "approval_external_binding")
public class ApprovalExternalBindingEntity {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
    @Column(name = "request_id", nullable = false)
    private UUID requestId;
    @Column(name = "corp_id", nullable = false)
    private String corpId;
    @Column(name = "process_code", nullable = false)
    private String processCode;
    @Column(name = "process_instance_id", nullable = false)
    private String processInstanceId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public String getCorpId() { return corpId; }
    public void setCorpId(String corpId) { this.corpId = corpId; }
    public String getProcessCode() { return processCode; }
    public void setProcessCode(String processCode) { this.processCode = processCode; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
