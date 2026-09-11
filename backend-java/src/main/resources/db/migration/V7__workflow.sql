-- ============================================================
--  V7__workflow.sql
--  Epic 5 工作流(US-401~407) — Week 11 MVP
--
--  设计:3 张表
--  workflows:工作流定义(节点列表 JSONB + 触发器 JSONB)
--  workflow_instances:执行实例
--  workflow_tasks:审批/通知/等待类任务
-- ============================================================

CREATE TABLE workflows (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(64) NOT NULL,
    title VARCHAR(128),
    description VARCHAR(500),
    collection_name VARCHAR(64) NOT NULL,  -- 关联到 collection_meta.name
    trigger JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {type: "manual"|"data_change"|"schedule", config: {...}}
    nodes JSONB NOT NULL DEFAULT '[]'::jsonb,    -- [{id, type: "approval"|"notification"|"condition"|"data_update"|"http", config: {...}}]
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID
);

CREATE INDEX idx_workflows_collection ON workflows(collection_name);
CREATE INDEX idx_workflows_tenant ON workflows(tenant_id);

CREATE TABLE workflow_instances (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,  -- PENDING | RUNNING | COMPLETED | FAILED
    trigger_data JSONB NOT NULL DEFAULT '{}'::jsonb,  -- 触发时的数据
    record_id VARCHAR(64),  -- 触发关联的记录 id
    current_node_index INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    tenant_id VARCHAR(64) NOT NULL
);

CREATE INDEX idx_workflow_instances_workflow ON workflow_instances(workflow_id);
CREATE INDEX idx_workflow_instances_status ON workflow_instances(status);

CREATE TABLE workflow_tasks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    instance_id UUID NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    node_id VARCHAR(64) NOT NULL,  -- 对应 nodes[] 中的 id
    node_type VARCHAR(32) NOT NULL,  -- APPROVAL / NOTIFICATION / ...
    assignee UUID,                    -- 审批人
    status VARCHAR(16) NOT NULL,     -- PENDING | APPROVED | REJECTED | COMPLETED
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_workflow_tasks_instance ON workflow_tasks(instance_id);
CREATE INDEX idx_workflow_tasks_assignee ON workflow_tasks(assignee);
CREATE INDEX idx_workflow_tasks_status ON workflow_tasks(status);

COMMENT ON TABLE workflows IS '工作流定义(Epic 5)';
COMMENT ON TABLE workflow_instances IS '工作流执行实例';
COMMENT ON TABLE workflow_tasks IS '工作流任务节点(审批/通知)';
