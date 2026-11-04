-- ============================================================
--  V77__org_unit_and_user_profile.sql
--  企业自用闭环 E1（组织架构）前置 + A-2（外部建号失败）止血。
--  任务书：docs/PLANS/enterprise-suite-capability-plan.md §T1.1
--
--  内容：
--   1. users.password_hash 放开 NOT NULL —— 外部托管账号（钉钉/企微/飞书/LDAP/OIDC）
--      无本地密码，只能走 SSO。A-2 的根因就是该列 NOT NULL 而建号不写密码。
--   2. users 新增组织/档案字段（部门/岗位/上级/手机/工号/状态/入职时间）。
--   3. 新增 org_unit 部门树表（E1 的组织 SSOT 本地只读镜像）。
--   4. 相关索引。
--
--  兼容性：全部 ADD COLUMN 带默认值或可空；存量账号 password_set 默认 TRUE，行为不变。
-- ============================================================

-- ---------- 1. A-2：外部托管账号无本地密码 ----------
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- password_set=false 表示该账号由外部平台托管，禁止本地密码登录
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_set BOOLEAN NOT NULL DEFAULT TRUE;
COMMENT ON COLUMN users.password_set IS '是否设置过本地密码；false=外部平台托管，只能 SSO 登录';

-- ---------- 2. 组织/档案字段 ----------
ALTER TABLE users ADD COLUMN IF NOT EXISTS department_id UUID;
ALTER TABLE users ADD COLUMN IF NOT EXISTS position      VARCHAR(128);
ALTER TABLE users ADD COLUMN IF NOT EXISTS manager_id    UUID;
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone         VARCHAR(32);
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url    VARCHAR(512);
ALTER TABLE users ADD COLUMN IF NOT EXISTS employee_no   VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS status        VARCHAR(16) DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS joined_at     TIMESTAMPTZ;

COMMENT ON COLUMN users.department_id IS '所属部门（org_unit.id）；为空时数据范围等同 SELF（D6）';
COMMENT ON COLUMN users.manager_id    IS '直属上级（users.id）；审批 approverType=MANAGER 的解析来源';
COMMENT ON COLUMN users.status        IS '人员状态: ACTIVE/RESIGNED/DISABLED';

-- ---------- 3. 部门树 ----------
CREATE TABLE IF NOT EXISTS org_unit (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   VARCHAR(64)  NOT NULL,
    parent_id   UUID         NULL REFERENCES org_unit (id) ON DELETE SET NULL,
    name        VARCHAR(255) NOT NULL,
    code        VARCHAR(128),
    -- 物化路径，形如 "/root/child/leaf"；子树查询用 path LIKE '/root/child%'
    path        VARCHAR(1024) NOT NULL DEFAULT '/',
    sort        INTEGER      NOT NULL DEFAULT 0,
    -- 来源: LOCAL（本地可编辑）/ DINGTALK / WECOM / FEISHU / LDAP
    source      VARCHAR(32)  NOT NULL DEFAULT 'LOCAL',
    external_id VARCHAR(128),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  org_unit            IS '组织架构部门树（E1）；外部来源子树只读镜像';
COMMENT ON COLUMN org_unit.path       IS '物化路径，用于子树前缀查询';
COMMENT ON COLUMN org_unit.source     IS 'LOCAL 可本地编辑；其余为外部平台托管（UI 只读）';
COMMENT ON COLUMN org_unit.external_id IS '外部平台部门 ID，按 (tenant_id, source, external_id) 幂等';

-- 外部平台同步幂等键（LOCAL 节点 external_id 为 NULL，PG 唯一索引允许多个 NULL）
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_unit_external
    ON org_unit (tenant_id, source, external_id)
    WHERE external_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_org_unit_tenant_parent ON org_unit (tenant_id, parent_id);
CREATE INDEX IF NOT EXISTS idx_org_unit_tenant_path   ON org_unit (tenant_id, path);

-- ---------- 4. 用户组织索引 ----------
CREATE INDEX IF NOT EXISTS idx_users_tenant_dept ON users (tenant_id, department_id);
CREATE INDEX IF NOT EXISTS idx_users_manager     ON users (manager_id);
