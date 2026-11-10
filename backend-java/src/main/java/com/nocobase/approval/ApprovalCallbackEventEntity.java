package com.nocobase.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 回调事件状态机；(corpId,eventId) 是幂等键，状态迁移由条件 UPDATE 加锁。 */
@Entity
@Table(name = "approval_callback_event")
public class ApprovalCallbackEventEntity {
    @EmbeddedId
    private ApprovalCallbackEventId id = new ApprovalCallbackEventId();
    @Column(name = "binding_id", nullable = false)
    private UUID bindingId;
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
    @Column(name = "process_instance_id", nullable = false)
    private String processInstanceId;
    @Column(name = "event_type", nullable = false)
    private String eventType = "finish";
    @Column(nullable = false)
    private String result;
    @Column(name = "processing_status", nullable = false)
    private String processingStatus = "RECEIVED";
    private String error;
    @Column(name = "worker_id")
    private String workerId;
    @Column(name = "claimed_at")
    private Instant claimedAt;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();
    @Column(name = "processed_at")
    private Instant processedAt;

    public String getCorpId() { return id.getCorpId(); }
    public void setCorpId(String corpId) { id.setCorpId(corpId); }
    public String getEventId() { return id.getEventId(); }
    public void setEventId(String eventId) { id.setEventId(eventId); }
    public UUID getBindingId() { return bindingId; }
    public void setBindingId(UUID bindingId) { this.bindingId = bindingId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(String processingStatus) { this.processingStatus = processingStatus; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
