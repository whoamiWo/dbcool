-- ============================================================
--  V1__init.sql
--  初始化:users 表 + seed 一个开发用户
--  密码 admin123 的 bcrypt hash(cost=10,2026-09-09 生成)
--  可以用 Python 重新生成:
--    python3 -c "import bcrypt; print(bcrypt.hashpw(b'admin123', bcrypt.gensalt(rounds=10)).decode())"
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_tenant ON users(tenant_id);

-- 开发用户 admin / admin123(bcrypt cost=10)
INSERT INTO users (id, username, password_hash, tenant_id, display_name, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'admin',
    '$2b$10$ZFPOnWhoeHlttyF88mVEDuOiIHMsfS7tH1WpWk1J2B03thOFCkzxS',
    'tenant_default',
    'Administrator',
    NOW()
);
