-- ============================================================
--  V6__acl.sql
--  Epic 4 权限(US-301~307)
--
--  设计(Week 10 简化):
--  - users 加 enabled 字段
--  - roles 表:租户内的角色
--  - user_roles:多对多
--  - acl_policies:权限(字段/行/操作)统一存 JSONB
-- ============================================================

-- 1. users 加 enabled 字段
ALTER TABLE users ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. roles 表
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(64) NOT NULL,
    description VARCHAR(500),
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_roles_tenant ON roles(tenant_id);

-- 3. user_roles 多对多
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_role ON user_roles(role_id);

-- 4. acl_policies 统一权限表
-- type: 'field' | 'row' | 'action'
-- subject: collection_name
-- config: JSONB 类型相关
CREATE TABLE acl_policies (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    type VARCHAR(16) NOT NULL,
    subject VARCHAR(64) NOT NULL,  -- collection_name
    action VARCHAR(16),            -- 'create' | 'read' | 'update' | 'delete' (仅 action 类型)
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_acl_role ON acl_policies(role_id);
CREATE INDEX idx_acl_subject ON acl_policies(subject);

COMMENT ON TABLE acl_policies IS '权限策略(US-303/304/305 统一表)';

-- 5. seed 默认角色
INSERT INTO roles (id, name, description, tenant_id, created_at)
VALUES
    ('00000000-0000-0000-0000-000000000010', 'admin', '租户内管理员,所有权限', 'tenant_default', NOW()),
    ('00000000-0000-0000-0000-000000000011', 'user', '普通用户,只读 + 创建', 'tenant_default', NOW());

-- 6. 把 admin 用户关联到 admin 角色
INSERT INTO user_roles (user_id, role_id)
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000010');
