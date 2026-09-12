-- ROW-level ACL(Week 14.5)
-- 针对单条记录级别的访问控制:owner / role / 动态表达式
-- 任何 policy 命中 = 允许(OR 组合);无 policy = 不受限
CREATE TABLE acl_row_policy (
    id            UUID PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    collection    VARCHAR(64) NOT NULL,
    principal_type VARCHAR(16) NOT NULL,    -- 'user' | 'role'
    principal_id  VARCHAR(64) NOT NULL,    -- user_id (UUID) 或 role 名
    action        VARCHAR(16) NOT NULL,    -- 'read' | 'create' | 'update' | 'delete'
    expression    JSONB NOT NULL,          -- {"field":"owner_id","op":"eq","value":"$currentUser"}
    priority      INT  NOT NULL DEFAULT 0, -- 数字越大越先匹配(预留)
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    description   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_acl_row_policy_tenant_collection
    ON acl_row_policy (tenant_id, collection, enabled);
CREATE INDEX idx_acl_row_policy_principal
    ON acl_row_policy (tenant_id, principal_type, principal_id);
CREATE INDEX idx_acl_row_policy_action
    ON acl_row_policy (tenant_id, collection, action);

COMMENT ON TABLE acl_row_policy IS '行级 ACL:基于表达式判断单条记录的可见性 / 可写性';
COMMENT ON COLUMN acl_row_policy.expression IS '表达式:{field, op:eq/neq/in/is_null, value} value 支持 $currentUser / $currentRoles 占位符';
