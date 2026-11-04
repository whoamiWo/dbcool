package com.nocobase.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 审批单据（E2）。
 *
 * <p>{@code status} 是 {@code workflow_instance.status} 的投影（单一事实，决策 D9）：
 * <pre>
 *   PENDING    → PENDING
 *   COMPLETED  → APPROVED
 *   FAILED     → REJECTED（本项目驳回以实例 FAILED 表达）
 *   TERMINATED → CANCELED（主动撤回）
 * </pre>
 */
@Entity
@Table(name = "approval_request")
public class ApprovalRequestEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELED = "CANCELED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "applicant_id", nullable = false)
    private UUID applicantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", columnDefinition = "jsonb", nullable = false)
    private String formDataJson = "{}";

    @Column(name = "status", nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "instance_id")
    private UUID instanceId;

    @Column(name = "current_task_id")
    private UUID currentTaskId;

    @Column(name = "external_instance_id")
    private String externalInstanceId;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }
    public UUID getApplicantId() { return applicantId; }
    public void setApplicantId(UUID applicantId) { this.applicantId = applicantId; }
    public String getFormDataJson() { return formDataJson; }
    public void setFormDataJson(String formDataJson) { this.formDataJson = formDataJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getInstanceId() { return instanceId; }
    public void setInstanceId(UUID instanceId) { this.instanceId = instanceId; }
    public UUID getCurrentTaskId() { return currentTaskId; }
    public void setCurrentTaskId(UUID currentTaskId) { this.currentTaskId = currentTaskId; }
    public String getExternalInstanceId() { return externalInstanceId; }
    public void setExternalInstanceId(String externalInstanceId) { this.externalInstanceId = externalInstanceId; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
