-- 审计日志(Week 14.5)
CREATE TABLE IF NOT EXISTS audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    username TEXT,
    action TEXT NOT NULL,         -- CREATE / UPDATE / DELETE / TRIGGER / APPROVE / REJECT / LOGIN / ...
    resource TEXT NOT NULL,        -- collection_name / workflow / role / user ...
    resource_id TEXT,              -- 记录 / 实体 ID(可选)
    payload_json TEXT,             -- 变更内容(快照)
    ip TEXT,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_tenant_created ON audit_log(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_resource ON audit_log(tenant_id, resource, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(tenant_id, user_id, created_at DESC);
