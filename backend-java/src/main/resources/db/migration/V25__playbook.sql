-- ============================================================
--  V25__playbook.sql
--  Playbook 轻量版：复用 workflow_instances / workflow_tasks 承载运行与 Checklist
--  依赖: V7__workflow.sql
-- ============================================================

-- 1. Playbook 定义表（只新增 1 张表）
--    Phase 48:列与 PlaybookEntity 对齐(yaml_source / channel_id / status / created_by)
CREATE TABLE IF NOT EXISTS playbook (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    category VARCHAR(32),
    icon VARCHAR(64),
    yaml_source TEXT NOT NULL,
    channel_id UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',   -- DRAFT | ACTIVE | ARCHIVED
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_playbook_tenant ON playbook(tenant_id, status);

-- 2. 复用 workflow_instances：绑定 IM 频道
ALTER TABLE workflow_instances
    ADD COLUMN IF NOT EXISTS channel_id UUID REFERENCES im_channel(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_wf_instance_channel ON workflow_instances(channel_id) WHERE channel_id IS NOT NULL;

-- 3. 复用 workflow_tasks：承载 Checklist 项
ALTER TABLE workflow_tasks
    ADD COLUMN IF NOT EXISTS title VARCHAR(255),
    ADD COLUMN IF NOT EXISTS due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_wf_task_due ON workflow_tasks(due_at) WHERE due_at IS NOT NULL;

COMMENT ON TABLE playbook IS 'Playbook 剧本定义（节点定义 JSON + checklist 元数据）';
COMMENT ON COLUMN workflow_instances.channel_id IS 'Playbook 运行实例绑定的 IM 频道';
COMMENT ON COLUMN workflow_tasks.title IS 'Checklist 项标题（Playbook 复用）';
COMMENT ON COLUMN workflow_tasks.due_at IS 'Checklist 项截止时间';
COMMENT ON COLUMN workflow_tasks.sort_order IS 'Checklist 项排序';
