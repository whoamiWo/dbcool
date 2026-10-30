-- ============================================================
--  V27_6__ldap_user_mapping_compat.sql
--  预建 ldap_user_mapping（含 tenant_id），修复"全新部署 V28 失败"问题。
--
--  背景（Wave4 离线包实测复现，2026-10-30）：
--    V28__platform_enhancement.sql:106 建表时 UNIQUE(ldap_uid, tenant_id)，
--    但表定义里**没有 tenant_id 列** → 全新库执行到该句必然失败：
--      ERROR: column "tenant_id" named in key does not exist
--    主库因后续迁移补过该列而从未暴露。
--
--  修复：在 V28 之前用 V27_6 预建完整表（含 tenant_id + 全部列 + 唯一约束）。
--    V28 的 CREATE TABLE IF NOT EXISTS 会跳过，索引也 IF NOT EXISTS → 通过。
--    主库已有该表 → 同样跳过，零影响。
--
--  版本号：27 < 27.5（auth_user 兼容）< 27.6 < 28。
-- ============================================================

CREATE TABLE IF NOT EXISTS ldap_user_mapping (
    id UUID PRIMARY KEY,
    ldap_dn VARCHAR(512) NOT NULL,
    ldap_uid VARCHAR(256) NOT NULL,
    local_user_id UUID REFERENCES auth_user(id) ON DELETE CASCADE,
    sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sync_error TEXT,
    last_synced_at TIMESTAMPTZ,
    ldap_attributes JSONB DEFAULT '{}'::jsonb,
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(ldap_uid, tenant_id)
);
