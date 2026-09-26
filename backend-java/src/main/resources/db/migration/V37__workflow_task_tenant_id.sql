-- PHASE 55 Stage 6 多租户隔离：workflow_tasks 补 tenant_id
--
-- 背景：WorkflowTaskRepository.findByAssigneeAndStatus(assignee, status) 按用户查询待办，
-- 不经过父实体（workflow_instances），原表无租户列 → 无法下推租户过滤。
-- 由于 UserTenantEntity 允许用户切换多租户，用户切到租户 B 后会看到租户 A 的任务
-- （跨租户越权）。本迁移补冗余租户列并回填，使租户过滤可下推到 SQL。

ALTER TABLE workflow_tasks ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

-- 回填存量：从所属工作流实例继承租户
UPDATE workflow_tasks t
SET tenant_id = (SELECT i.tenant_id FROM workflow_instances i WHERE i.id = t.instance_id)
WHERE t.tenant_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_workflow_tasks_tenant_assignee
    ON workflow_tasks (tenant_id, assignee);
