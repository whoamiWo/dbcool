-- ============================================================
-- PHASE96 T1: 外部用户映射表（第三方平台用户 → 我方用户）
--
-- 背景：InboundMessageService.mapExternalUser() 此前恒返回硬编码 UUID
-- 00000000-0000-0000-0000-000000000001，导致所有第三方入站消息的作者都是同一个人，
-- 且"映射失败"分支是死代码。本表让映射可持久化、可按租户隔离。
--
-- 设计：唯一键为 (platform, external_user_id, tenant_id)，
-- 与既有 ldap_user_mapping 的范式一致（见 V28__platform_enhancement.sql:106-121）。
--
-- PHASE96 B5 修正：
--   - local_user_id 外键指向 users(id)（非遗留表 auth_user —— 该表在库中不存在，
--     指向它会让全新环境的 V54 直接失败）
--   - metadata 用 TEXT（非 JSONB）：Hibernate 6 + H2Dialect 识别 jsonb 类型名失败，
--     且与实体 ExternalUserMappingEntity.metadata(String) 一致
-- ============================================================

CREATE TABLE IF NOT EXISTS external_user_mapping (
    id UUID PRIMARY KEY,
    platform VARCHAR(64) NOT NULL,            -- slack / mattermost / feishu / dingtalk / wecom ...
    external_user_id VARCHAR(256) NOT NULL,   -- 第三方平台内的用户标识
    local_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(256),                -- 第三方展示名（便于排障）
    metadata TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (platform, external_user_id, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_external_user_mapping_lookup
    ON external_user_mapping(platform, external_user_id, tenant_id);

CREATE INDEX IF NOT EXISTS idx_external_user_mapping_local
    ON external_user_mapping(local_user_id);
