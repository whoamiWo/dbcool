-- C3 B7: ACL 数据范围控制字段
ALTER TABLE acl_policies
    ADD COLUMN data_scope_type   VARCHAR(16) DEFAULT 'ALL',
    ADD COLUMN data_scope_config JSONB DEFAULT NULL;

COMMENT ON COLUMN acl_policies.data_scope_type IS '数据范围: ALL/DEPT/SELF/CUSTOM';
COMMENT ON COLUMN acl_policies.data_scope_config IS '自定义数据范围配置(JSON)';

CREATE INDEX IF NOT EXISTS idx_acl_policies_scope
    ON acl_policies (tenant_id, data_scope_type);
