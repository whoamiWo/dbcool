-- US-410: 工作流版本管理表
-- 用途：发布时写快照，回滚时用快照覆盖 workflow 定义
-- 设计约束：
--   - UNIQUE(workflow_id, version) 确保同一工作流版本号唯一
--   - definition_json 使用 TEXT 而非 JSONB 以兼容 H2（参考 V61 约定）
--   - published_at 记录发布时间，published_by 记录操作人
CREATE TABLE IF NOT EXISTS workflow_version (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workflow_id VARCHAR(36) NOT NULL,
    version INT NOT NULL,
    definition_json TEXT NOT NULL,
    published_by VARCHAR(64),
    published_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workflow_version (workflow_id, version)
);

CREATE INDEX IF NOT EXISTS idx_workflow_version_workflow_id ON workflow_version (workflow_id);
CREATE INDEX IF NOT EXISTS idx_workflow_version_tenant_id ON workflow_version (tenant_id);
