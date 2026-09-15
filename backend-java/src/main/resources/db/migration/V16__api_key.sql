-- ============================================================
--  V16__api_key.sql
--  Week 42 D5.2: API Key 表(外部系统认证)
--
--  设计要点:
--  - id UUID 主键
--  - key_prefix 前 8 字符(展示用,非敏感)
--  - key_hash SHA-256 hex(64 char),唯一索引
--  - scopes 逗号分隔权限
--  - 软删除 revoked_at
--  - 复合索引 (tenant_id, revoked_at) 支持管理 UI 查询
-- ============================================================

CREATE TABLE api_key (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    key_prefix    VARCHAR(8)   NOT NULL,
    key_hash      VARCHAR(64)  NOT NULL UNIQUE,
    scopes        VARCHAR(256),
    created_by    UUID         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    tenant_id     VARCHAR(64)  NOT NULL
);

CREATE INDEX idx_apikey_prefix ON api_key(key_prefix);
CREATE INDEX idx_apikey_tenant_active ON api_key(tenant_id, revoked_at);
