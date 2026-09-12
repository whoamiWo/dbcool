-- US-308 角色继承:角色可以有可选的 parent_role_id
-- self-reference,on delete SET NULL(删除 parent 不级联杀子)
ALTER TABLE roles ADD COLUMN parent_role_id UUID
    REFERENCES roles(id) ON DELETE SET NULL;

CREATE INDEX idx_roles_parent ON roles(parent_role_id);
CREATE INDEX idx_roles_tenant_parent ON roles(tenant_id, parent_role_id);
