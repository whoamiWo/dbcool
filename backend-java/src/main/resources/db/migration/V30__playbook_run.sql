-- ============================================================
--  V30__playbook_run.sql  (Phase 48 F2 — Playbook 折中版深化)
--  1 张 playbook_run 表承载运行实例:JSONB checklist / events + SLA due_at
--  执行仍复用 WorkflowEngine(通过 playbook.workflow_id 缓存编译后的节点图,
--  消除"每次运行 new WorkflowEntity 落库"的污染)
--  依赖: V25__playbook.sql
-- ============================================================

-- 1. playbook 加 workflow_id:首次运行时编译定义保存 WorkflowEntity 并缓存，后续复用
ALTER TABLE playbook
    ADD COLUMN IF NOT EXISTS workflow_id UUID REFERENCES workflows(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_playbook_workflow ON playbook(workflow_id) WHERE workflow_id IS NOT NULL;

-- 2. 运行实例表 (本轮唯一新增表)
CREATE TABLE IF NOT EXISTS playbook_run (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id VARCHAR(64) NOT NULL,
    playbook_id UUID NOT NULL REFERENCES playbook(id) ON DELETE CASCADE,
    channel_id UUID,
    instance_id UUID,                              -- 对应 workflow_instances.id
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',  -- RUNNING | FINISHED | OVERDUE
    due_at TIMESTAMPTZ,                            -- SLA 到期时间
    checklist_json JSONB NOT NULL DEFAULT '[]'::jsonb,  -- [{title,done,dueAt}]
    events_json JSONB NOT NULL DEFAULT '[]'::jsonb,     -- [{at,event,detail}] 整读整写
    retrospective_page_id UUID,                    -- 完成后生成的 Wiki 复盘页
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_playbook_run_tenant ON playbook_run(tenant_id, playbook_id);
CREATE INDEX IF NOT EXISTS idx_playbook_run_due ON playbook_run(due_at) WHERE due_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_playbook_run_status ON playbook_run(status);

COMMENT ON TABLE playbook_run IS 'Playbook 运行实例 (Checklist/事件走 JSONB 整读整写)';
COMMENT ON COLUMN playbook_run.due_at IS 'SLA 到期时间 (到期未完成 → OVERDUE)';
