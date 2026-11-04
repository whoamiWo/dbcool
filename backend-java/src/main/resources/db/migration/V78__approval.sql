-- ============================================================
--  V78__approval.sql
--  企业自用闭环 E2（审批单据）。
--  任务书：docs/PLANS/enterprise-suite-capability-plan.md §T3.1
--
--  设计决策 D9：审批链由 WorkflowEngine 驱动（单引擎），
--  approval_template.workflow_id 必填；提交 = 触发 workflow 实例。
--  状态映射（单一事实 = workflow_instance.status）：
--    PENDING→PENDING / COMPLETED→APPROVED / FAILED(驳回)→REJECTED / TERMINATED→CANCELED
-- ============================================================

CREATE TABLE IF NOT EXISTS approval_template (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id     VARCHAR(64)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    icon          VARCHAR(64),
    category      VARCHAR(64),
    form_id       VARCHAR(128),
    -- D9：审批链必须由工作流引擎驱动，禁止第二套流程解释器
    workflow_id   UUID         NOT NULL,
    -- USER / MANAGER / ROLE（与 ApprovalNodeHandler 的 approverType 对齐）
    approver_type VARCHAR(32)  NOT NULL DEFAULT 'USER',
    -- 钉钉审批模板编码（D4：推送开关默认关，本地为 SSOT）
    external_process_code VARCHAR(128),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_approval_template_tenant
    ON approval_template (tenant_id, enabled);

CREATE TABLE IF NOT EXISTS approval_request (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id            VARCHAR(64) NOT NULL,
    template_id          UUID        NOT NULL,
    applicant_id         UUID        NOT NULL,
    form_data            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- PENDING / APPROVED / REJECTED / CANCELED
    status               VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    instance_id          UUID,
    current_task_id      UUID,
    external_instance_id VARCHAR(128),
    submitted_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at          TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_approval_request_tenant_applicant
    ON approval_request (tenant_id, applicant_id, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_approval_request_instance
    ON approval_request (instance_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_request_external
    ON approval_request (tenant_id, external_instance_id)
    WHERE external_instance_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS approval_cc (
    request_id UUID NOT NULL REFERENCES approval_request (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (request_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_approval_cc_user ON approval_cc (user_id);
