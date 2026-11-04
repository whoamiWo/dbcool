package com.nocobase.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 审批模板（E2）。审批链由 {@code workflow_id} 指向的工作流定义驱动（决策 D9）。
 */
@Entity
@Table(name = "approval_template")
public class ApprovalTemplateEntity {

    /** 审批人来源：指定人 / 发起人上级 / 角色成员。 */
    public static final String APPROVER_USER = "USER";
    public static final String APPROVER_MANAGER = "MANAGER";
    public static final String APPROVER_ROLE = "ROLE";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "icon")
    private String icon;

    @Column(name = "category")
    private String category;

    @Column(name = "form_id")
    private String formId;

    /** D9：必填——审批链必须由工作流引擎解释。 */
    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "approver_type", nullable = false)
    private String approverType = APPROVER_USER;

    /** 钉钉 OA 审批模板编码；为空表示不需要推送钉钉。 */
    @Column(name = "external_process_code")
    private String externalProcessCode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFormId() { return formId; }
    public void setFormId(String formId) { this.formId = formId; }
    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }
    public String getApproverType() { return approverType; }
    public void setApproverType(String approverType) { this.approverType = approverType; }
    public String getExternalProcessCode() { return externalProcessCode; }
    public void setExternalProcessCode(String externalProcessCode) { this.externalProcessCode = externalProcessCode; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
